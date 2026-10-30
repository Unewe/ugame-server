package com.ugame.game.room;

import com.ugame.game.ProtoMessages;
import com.ugame.game.net.KcpChannel;
import com.ugame.game.redis.MatchTicket;
import com.ugame.protocol.ByteWriter;
import com.ugame.protocol.InputPacket;
import com.ugame.protocol.MessageType;
import com.ugame.protocol.SnapshotPacket;
import com.ugame.protocol.pb.JoinResult;
import com.ugame.protocol.pb.MatchEnd;
import com.ugame.protocol.pb.MatchStart;
import com.ugame.protocol.pb.PlayerInfo;
import com.ugame.protocol.pb.PlayerResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Один матч. Вся логика выполняется в {@link #tick()}, который планировщик вызывает с фиксированной
 * частотой и никогда не запускает параллельно с самим собой. Сетевые потоки общаются с комнатой
 * только через {@link #post(Runnable)} — поэтому внутри комнаты нет блокировок.
 */
public final class Room {
    private static final Logger log = LoggerFactory.getLogger(Room.class);
    private static final int ENDED_LINGER_SEC = 3;

    enum Phase { WAITING, RUNNING, ENDED, CLOSED }

    private final String matchId;
    private final int expectedPlayers;
    private final RoomSettings settings;
    private final Consumer<Room> onClosed;
    private final MatchResultSink resultSink;

    private final Queue<Runnable> inbox = new ConcurrentLinkedQueue<>();
    private final List<ClientSession> sessions = new ArrayList<>();
    private final World world;
    private final ByteWriter entityWriter = new ByteWriter(1024);
    private final ByteWriter packetWriter = new ByteWriter(1200);
    private final long createdAt = System.currentTimeMillis();

    private Phase phase = Phase.WAITING;
    private long startTick;
    private long endTick;
    private long endedAt;

    /** Куда отдать результат матча (Redis Stream в продакшене, заглушка в тестах). */
    public interface MatchResultSink {
        void publish(String matchId, int winnerTeam, String results);
    }

    Room(String matchId, int expectedPlayers, RoomSettings settings, MatchResultSink resultSink, Consumer<Room> onClosed) {
        this.matchId = matchId;
        this.expectedPlayers = expectedPlayers;
        this.settings = settings;
        this.resultSink = resultSink;
        this.onClosed = onClosed;
        this.world = new World(settings.tickRate());
    }

    public String matchId() {
        return matchId;
    }

    /** Потокобезопасно: выполнить действие в потоке комнаты на ближайшем тике. */
    public void post(Runnable action) {
        inbox.add(action);
    }

    // ------------------------------------------------------------------ тик

    void tick() {
        try {
            Runnable action;
            while ((action = inbox.poll()) != null) {
                action.run();
            }
            switch (phase) {
                case WAITING -> tickWaiting();
                case RUNNING -> tickRunning();
                case ENDED -> tickEnded();
                case CLOSED -> { }
            }
        } catch (RuntimeException e) {
            // Ошибка в логике не должна убивать поток планировщика и другие комнаты.
            log.error("Room {}: error in tick", matchId, e);
        }
    }

    private void tickWaiting() {
        long waited = System.currentTimeMillis() - createdAt;
        boolean timeout = waited >= settings.joinTimeoutSec() * 1000L;
        if (sessions.size() >= expectedPlayers || (timeout && !sessions.isEmpty())) {
            startMatch();
        } else if (timeout) {
            log.info("Room {}: no one showed up, closing it", matchId);
            close();
        }
    }

    private void tickRunning() {
        world.step();
        if (world.tick() % settings.snapshotEvery() == 0) {
            broadcastSnapshot();
        }
        if (world.tick() >= endTick) {
            endMatch();
        }
    }

    private void tickEnded() {
        if (System.currentTimeMillis() - endedAt >= ENDED_LINGER_SEC * 1000L) {
            for (ClientSession s : sessions) {
                s.connection().close("match finished");
            }
            close();
        }
    }

    // ------------------------------------------------------------------ события от сети

    void handleJoin(ClientSession session, MatchTicket ticket) {
        if (phase == Phase.ENDED || phase == Phase.CLOSED) {
            session.send(ProtoMessages.encode(MessageType.S2C_JOIN_RESULT,
                    JoinResult.newBuilder().setOk(false).setError("match_finished").build()), KcpChannel.RELIABLE);
            return;
        }
        // повторный вход того же игрока (переподключение): забираем его бойца себе
        World.Brawler brawler = null;
        for (ClientSession existing : new ArrayList<>(sessions)) {
            if (existing.playerId == ticket.playerId()) {
                brawler = existing.brawler;
                sessions.remove(existing);
                existing.connection().close("sign in from another device");
            }
        }
        if (brawler == null) {
            for (World.Brawler b : world.brawlers()) {
                if (b.playerId == ticket.playerId()) {
                    brawler = b; // вернулся после обрыва связи
                }
            }
        }
        if (brawler == null) {
            brawler = world.spawnBrawler(ticket.playerId(), ticket.team());
        }

        session.playerId = ticket.playerId();
        session.username = ticket.username();
        session.brawler = brawler;
        session.bindRoom(this);
        sessions.add(session);

        session.send(ProtoMessages.encode(MessageType.S2C_JOIN_RESULT, JoinResult.newBuilder()
                .setOk(true)
                .setEntityId(brawler.id)
                .setTeam(brawler.team)
                .setTickRate(settings.tickRate())
                .setMatchId(matchId)
                .build()), KcpChannel.RELIABLE);

        log.info("Room {}: {} entered (id={}, team {}), players {}/{}",
                matchId, ticket.username(), ticket.playerId(), brawler.team, sessions.size(), expectedPlayers);

        if (phase == Phase.RUNNING) {
            session.send(matchStartMessage(), KcpChannel.RELIABLE); // поздний вход
        }
    }

    void handleInput(ClientSession session, List<InputPacket.Command> commands) {
        if (phase == Phase.RUNNING && sessions.contains(session)) {
            session.applyInputs(world, commands);
        }
    }

    void handleLeave(ClientSession session) {
        if (sessions.remove(session)) {
            // Боец остаётся на арене (как в Brawl Stars), игрок может переподключиться с новым билетом.
            log.info("Room {}: {} disconnected", matchId, session.username);
        }
    }

    // ------------------------------------------------------------------ фазы

    private void startMatch() {
        phase = Phase.RUNNING;
        startTick = world.tick();
        endTick = startTick + (long) settings.matchDurationSec() * settings.tickRate();
        byte[] start = matchStartMessage();
        for (ClientSession s : sessions) {
            s.send(start, KcpChannel.RELIABLE); // массив только читается транспортом — можно разделять
        }
        log.info("Room {}: match started, {} players", matchId, sessions.size());
    }

    private byte[] matchStartMessage() {
        MatchStart.Builder msg = MatchStart.newBuilder()
                .setMatchId(matchId)
                .setDurationSec(settings.matchDurationSec())
                .setStartTick((int) startTick);
        for (ClientSession s : sessions) {
            msg.addPlayers(PlayerInfo.newBuilder()
                    .setEntityId(s.brawler.id)
                    .setPlayerId(s.playerId)
                    .setUsername(s.username)
                    .setTeam(s.brawler.team));
        }
        return ProtoMessages.encode(MessageType.S2C_MATCH_START, msg.build());
    }

    private void endMatch() {
        phase = Phase.ENDED;
        endedAt = System.currentTimeMillis();
        int s0 = world.score(0);
        int s1 = world.score(1);
        int winner = s0 == s1 ? -1 : (s0 > s1 ? 0 : 1);

        MatchEnd.Builder msg = MatchEnd.newBuilder().setMatchId(matchId).setWinnerTeam(winner);
        StringJoiner results = new StringJoiner(",");
        for (World.Brawler b : world.brawlers()) {
            msg.addResults(PlayerResult.newBuilder()
                    .setPlayerId(b.playerId).setTeam(b.team).setKills(b.kills).setDeaths(b.deaths));
            results.add(b.playerId + ":" + b.team + ":" + b.kills + ":" + b.deaths);
        }
        byte[] end = ProtoMessages.encode(MessageType.S2C_MATCH_END, msg.build());
        for (ClientSession s : sessions) {
            s.send(end, KcpChannel.RELIABLE);
        }
        resultSink.publish(matchId, winner, results.toString());
        log.info("Room {}: match completed, score {}:{}", matchId, s0, s1);
    }

    private void close() {
        if (phase != Phase.CLOSED) {
            phase = Phase.CLOSED;
            onClosed.accept(this);
        }
    }

    // ------------------------------------------------------------------ снапшоты

    private void broadcastSnapshot() {
        List<SnapshotPacket.Entity> entities = world.snapshotEntities();
        entityWriter.reset();
        SnapshotPacket.writeEntities(entityWriter, entities);

        long ticksLeft = Math.max(0, endTick - world.tick());
        int timeLeftSec = (int) ((ticksLeft + settings.tickRate() - 1) / settings.tickRate());

        for (ClientSession s : sessions) {
            int ack = Math.max(0, s.brawler.lastInputSeq) & 0xFFFF;
            packetWriter.reset();
            SnapshotPacket.writeHeader(packetWriter,
                    new SnapshotPacket.Header(world.tick(), ack, timeLeftSec,
                            Math.min(255, world.score(0)), Math.min(255, world.score(1))),
                    entities.size());
            packetWriter.bytes(entityWriter.array(), 0, entityWriter.size());
            s.send(packetWriter.toArray(), KcpChannel.UNRELIABLE);
        }
    }
}
