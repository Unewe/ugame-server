package com.ugame.game.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ugame.protocol.ByteReader;
import com.ugame.protocol.ByteWriter;
import com.ugame.protocol.InputPacket;
import com.ugame.protocol.SnapshotPacket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Весь путь событий снарядов: World → снапшот (как Room.broadcastSnapshot) → сеть с потерями
 * и задержкой → разбор на клиенте → подтверждение обратно во вводе. Ни одно событие не должно
 * потеряться, а опоздание — быть не больше нескольких тиков.
 */
class ShotEventsDeliveryTest {
    private static final int TICK_RATE = 30;
    private static final int ONE_WAY_TICKS = 3;   // пинг ~200 мс

    @Test
    void eventsSurviveHeavyPacketLoss() {
        Random rnd = new Random(42);
        World w = new World(TICK_RATE);
        WorldDriver d = new WorldDriver(w);
        Brawler shooter = w.spawnBrawler(1, 0);
        Brawler target = w.spawnBrawler(2, 1);
        shooter.x = 10f;
        shooter.y = 2f;
        target.x = 10f;
        target.y = 6f;

        Map<Integer, Long> spawnedOnServer = new HashMap<>();   // id → тик выстрела
        Map<Integer, Long> hitOnServer = new HashMap<>();
        Map<Integer, Long> spawnSeenByClient = new HashMap<>(); // id → тик сервера, когда клиент узнал
        Map<Integer, Long> hitSeenByClient = new HashMap<>();

        List<byte[]> toClient = new ArrayList<>();   // пакеты в пути: [тик доставки → пакет]
        List<Long> toClientAt = new ArrayList<>();
        List<Long> toServerAck = new ArrayList<>();
        List<Long> toServerAt = new ArrayList<>();
        long serverAcked = -1;  // ClientSession.ackedSnapshotTick
        long clientLatest = 0;  // последний снапшот у клиента
        int maxPacket = 0;

        List<SnapshotPacket.ShotSpawn> spawns = new ArrayList<>();
        List<SnapshotPacket.ShotHit> hits = new ArrayList<>();
        ByteWriter out = new ByteWriter(1200);

        for (int t = 0; t < 1500; t++) {
            // --- сервер: подтверждения клиента, ввод, тик
            for (int i = toServerAt.size() - 1; i >= 0; i--) {
                if (toServerAt.get(i) <= w.tick()) {
                    serverAcked = Math.max(serverAcked, toServerAck.get(i));
                    toServerAt.remove(i);
                    toServerAck.remove(i);
                }
            }
            if (t % 15 == 0) {
                target.hp = World.BRAWLER_MAX_HP; // бессмертная мишень
                d.input(shooter, 0, 0, (float) (Math.PI / 2), t % 60 == 0
                        ? InputPacket.Buttons.ATTACK : InputPacket.Buttons.ATTACK | InputPacket.Buttons.SUPER);
                shooter.superCharge = World.SUPER_CHARGE_DAMAGE;
            }
            d.steps(1);
            for (var s : WorldDriver.spawns(w)) {
                spawnedOnServer.putIfAbsent(s.id(), s.tick());
            }
            for (var h : WorldDriver.hits(w)) {
                hitOnServer.putIfAbsent(h.id(), h.tick());
            }

            // --- снапшот этому клиенту, как в Room.broadcastSnapshot
            out.reset();
            List<SnapshotPacket.Entity> entities = w.snapshotEntities();
            SnapshotPacket.writeHeader(out, new SnapshotPacket.Header(w.tick(), 0, 0, 0, 0, w.ownState(shooter)),
                    entities.size());
            SnapshotPacket.writeEntities(out, entities);
            w.shotEventsAfter(serverAcked, spawns, hits);
            SnapshotPacket.writeShotEvents(out, w.tick(), spawns, hits);
            maxPacket = Math.max(maxPacket, out.size());
            if (rnd.nextDouble() >= 0.3) { // 30 % потерь
                toClient.add(out.toArray());
                toClientAt.add(w.tick() + ONE_WAY_TICKS + rnd.nextInt(2));
            }

            // --- клиент: принимает снапшоты, отвечает подтверждением в каждом пакете ввода
            for (int i = toClientAt.size() - 1; i >= 0; i--) {
                if (toClientAt.get(i) <= w.tick()) {
                    byte[] packet = toClient.remove(i);
                    toClientAt.remove(i);
                    var snap = SnapshotPacket.readBody(new ByteReader().wrap(packet, 1, packet.length - 1));
                    if (snap.header().tick() <= clientLatest) {
                        continue; // старый — как MatchView.AddSnapshot
                    }
                    clientLatest = snap.header().tick();
                    for (var s : snap.spawns()) {
                        assertEquals((long) spawnedOnServer.get(s.id()), s.tick(), "тик выстрела восстановлен из age");
                        spawnSeenByClient.putIfAbsent(s.id(), w.tick());
                    }
                    for (var h : snap.hits()) {
                        hitSeenByClient.putIfAbsent(h.id(), w.tick());
                    }
                }
            }
            if (rnd.nextDouble() >= 0.3) {
                toServerAck.add(clientLatest);
                toServerAt.add(w.tick() + ONE_WAY_TICKS);
            }
        }

        assertTrue(spawnedOnServer.size() > 300, "выстрелов: " + spawnedOnServer.size());
        assertTrue(hitOnServer.size() > 50, "попаданий: " + hitOnServer.size());
        assertTrue(maxPacket <= SnapshotPacket.MAX_SIZE);

        int worstSpawnDelay = 0;
        for (var e : spawnedOnServer.entrySet()) {
            if (e.getValue() > w.tick() - 10) {
                continue; // последние выстрелы ещё в пути
            }
            assertTrue(spawnSeenByClient.containsKey(e.getKey()), "выстрел " + e.getKey() + " потерян");
            worstSpawnDelay = Math.max(worstSpawnDelay, (int) (spawnSeenByClient.get(e.getKey()) - e.getValue()));
        }
        for (var e : hitOnServer.entrySet()) {
            if (e.getValue() <= w.tick() - 10) {
                assertTrue(hitSeenByClient.containsKey(e.getKey()), "попадание " + e.getKey() + " потеряно");
            }
        }
        // в пути 3–4 тика; несколько потерь подряд добавляют по тику
        assertTrue(worstSpawnDelay <= 10, "худшее опоздание выстрела, тиков: " + worstSpawnDelay);
    }
}
