package com.ugame.game.room;

import com.ugame.game.redis.MatchTicket;
import com.ugame.protocol.InputPacket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Создаёт комнаты и раздаёт их тики по пулу потоков. Каждая комната тикает с фиксированной
 * частотой; ScheduledExecutorService гарантирует, что тики одной комнаты не идут параллельно.
 */
public final class RoomManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

    private final RoomSettings settings;
    private final Room.MatchResultSink resultSink;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<Room, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public RoomManager(RoomSettings settings, Room.MatchResultSink resultSink) {
        this.settings = settings;
        this.resultSink = resultSink;
        this.scheduler = Executors.newScheduledThreadPool(settings.threads(), new NamedThreadFactory("room-tick-"));
    }

    public int roomCount() {
        return rooms.size();
    }

    /** Вызывается из любого потока после успешной проверки билета. */
    public void join(ClientSession session, MatchTicket ticket) {
        Room room = rooms.computeIfAbsent(ticket.matchId(), id -> createRoom(id, ticket.matchSize()));
        room.post(() -> room.handleJoin(session, ticket));
    }

    public void input(ClientSession session, List<InputPacket.Command> commands) {
        Room room = session.room();
        if (room != null) {
            room.post(() -> room.handleInput(session, commands));
        }
    }

    public void leave(ClientSession session) {
        Room room = session.room();
        if (room != null) {
            room.post(() -> room.handleLeave(session));
        }
    }

    private Room createRoom(String matchId, int expectedPlayers) {
        Room room = new Room(matchId, Math.max(1, expectedPlayers), settings, resultSink, this::onRoomClosed);
        long periodNanos = 1_000_000_000L / settings.tickRate();
        tasks.put(room, scheduler.scheduleAtFixedRate(room::tick, periodNanos, periodNanos, TimeUnit.NANOSECONDS));
        log.info("Создана комната {} на {} игроков", matchId, expectedPlayers);
        return room;
    }

    private void onRoomClosed(Room room) {
        rooms.remove(room.matchId(), room);
        ScheduledFuture<?> task = tasks.remove(room);
        if (task != null) {
            task.cancel(false);
        }
        log.info("Комната {} закрыта", room.matchId());
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
