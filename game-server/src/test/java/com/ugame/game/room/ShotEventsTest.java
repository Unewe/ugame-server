package com.ugame.game.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ugame.protocol.InputPacket;
import com.ugame.protocol.Quantize;
import com.ugame.protocol.SnapshotPacket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Снаряды в снапшоте — событиями «выстрел» и «попадание»; полёт клиент считает сам. */
class ShotEventsTest {
    private static final int TICK_RATE = 30;
    private static final float UP = (float) (Math.PI / 2);

    private World w;
    private WorldDriver d;
    private Brawler shooter;
    private Brawler target;

    private void setUp(float targetX) {
        w = new World(TICK_RATE);
        d = new WorldDriver(w);
        shooter = w.spawnBrawler(1, 0);
        target = w.spawnBrawler(2, 1);
        shooter.x = 10f;
        shooter.y = 2f;
        target.x = targetX;
        target.y = 6f;
        d.steps(5);
    }

    private void fire() {
        d.input(shooter, 0, 0, UP, InputPacket.Buttons.ATTACK);
        d.steps(1);
    }

    @Test
    void spawnEventDescribesTheShot() {
        setUp(15f);
        fire();
        var spawn = WorldDriver.spawns(w).get(0);
        assertEquals(w.tick(), spawn.tick());
        assertEquals(shooter.id, spawn.owner());
        assertEquals(shooter.team, spawn.team());
        assertEquals(SnapshotPacket.ShotKind.NORMAL, spawn.kind());
        assertEquals(Quantize.angleToU16(UP), spawn.angle());
        assertTrue(spawn.id() >= World.FIRST_PROJECTILE_ID, "id снарядов не пересекаются с id бойцов");
        // точка вылета — край бойца в сторону выстрела
        assertEquals(Quantize.posToI16(10f), spawn.x());
        assertEquals(Quantize.posToI16(2f + ShotType.NORMAL.hitDistance()), spawn.y());
    }

    /** Клиент: позиция в тике T = точка + направление × скорость × (T − тик выстрела + 1) тиков. */
    @Test
    void clientCanReconstructTheFlightFromTheSpawnEvent() {
        setUp(15f); // цель в стороне — снаряд долетает до конца дальности
        fire();
        var spawn = WorldDriver.spawns(w).get(0);
        float angle = Quantize.u16ToAngle(spawn.angle());
        int ticks = 0;
        while (w.projectiles().iterator().hasNext()) {
            Projectile p = w.projectiles().iterator().next();
            float flight = ShotType.NORMAL.speed / TICK_RATE * (w.tick() - spawn.tick() + 1);
            float x = spawn.x() / 100f + (float) Math.cos(angle) * flight;
            float y = spawn.y() / 100f + (float) Math.sin(angle) * flight;
            assertEquals(p.x, x, 0.01f);
            assertEquals(p.y, y, 0.01f);
            d.steps(1);
            ticks++;
        }
        // и исчезает там же, где клиент перестанет его рисовать: на конце дальности, без события
        float flightAtEnd = ShotType.NORMAL.speed / TICK_RATE * (w.tick() - spawn.tick() + 1);
        assertTrue(flightAtEnd >= ShotType.NORMAL.range);
        assertTrue(ticks > 10);
        assertEquals(0, WorldDriver.hits(w).size(), "конец дальности клиент считает сам — события нет");
    }

    @Test
    void hitProducesHitEvent() {
        setUp(10f);
        fire();
        d.steps(10);
        assertEquals(World.BRAWLER_MAX_HP - ShotType.NORMAL.damage, target.hp);
        var hits = WorldDriver.hits(w);
        assertEquals(1, hits.size());
        assertEquals(WorldDriver.spawns(w).get(0).id(), hits.get(0).id());
    }

    @Test
    void eventsRepeatUntilTheClientAcknowledgesThem() {
        setUp(15f);
        fire();
        long fired = w.tick();
        d.steps(3);
        List<SnapshotPacket.ShotSpawn> spawns = new ArrayList<>();
        List<SnapshotPacket.ShotHit> hits = new ArrayList<>();

        w.shotEventsAfter(fired - 1, spawns, hits); // клиент подтвердил снапшот до выстрела
        assertEquals(1, spawns.size(), "ещё не подтверждено — повторяем");
        w.shotEventsAfter(fired, spawns, hits);     // подтвердил снапшот, в котором был выстрел
        assertEquals(0, spawns.size());
    }

    @Test
    void oldEventsAreForgottenButOutliveEveryProjectile() {
        for (ShotType type : ShotType.values()) {
            float lifetimeTicks = type.range / type.speed * TICK_RATE;
            assertTrue(lifetimeTicks < World.SHOT_EVENT_KEEP_TICKS,
                    "переподключившийся клиент должен получить выстрелы всех летящих снарядов");
        }
        assertTrue(World.SHOT_EVENT_KEEP_TICKS <= SnapshotPacket.MAX_EVENT_AGE);

        setUp(15f);
        fire();
        d.steps(World.SHOT_EVENT_KEEP_TICKS);
        assertEquals(1, WorldDriver.spawns(w).size());
        d.steps(1);
        assertEquals(0, WorldDriver.spawns(w).size());
    }
}
