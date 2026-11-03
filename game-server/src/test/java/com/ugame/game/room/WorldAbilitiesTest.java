package com.ugame.game.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ugame.protocol.InputPacket;
import com.ugame.protocol.Quantize;
import com.ugame.protocol.SnapshotPacket;
import org.junit.jupiter.api.Test;

class WorldAbilitiesTest {
    private static final int TICK_RATE = 30;

    private WorldDriver d;

    private WorldDriver driver(World w) {
        if (d == null || d.w != w) {
            d = new WorldDriver(w);
        }
        return d;
    }

    private void press(World w, Brawler b, float aim, int buttons) {
        driver(w).input(b, 0, 0, aim, buttons);
    }

    /** Тики; каждый боец шлёт команду на каждый тик, как настоящий клиент. */
    private void steps(World w, int n) {
        driver(w).steps(n);
    }

    @Test
    void dodgeDashesAndHasCooldown() {
        World w = new World(TICK_RATE);
        Brawler b = w.spawnBrawler(1, 0);
        float startY = b.y;

        press(w, b, (float) (Math.PI / 2), InputPacket.Buttons.DODGE); // стоим — рывок по прицелу (вверх)
        steps(w, 1);
        assertTrue(b.dashTicksLeft > 0);
        steps(w, 10);
        assertEquals(startY + World.DASH_DISTANCE, b.y, 0.01);

        // кулдаун: повторное нажатие сразу не срабатывает
        press(w, b, (float) (Math.PI / 2), InputPacket.Buttons.DODGE);
        steps(w, 1);
        assertEquals(0, b.dashTicksLeft);
        assertTrue(w.ownState(b).dodgeCooldownTicks() > 0);

        steps(w, Math.round(World.DODGE_COOLDOWN_SEC * TICK_RATE));
        press(w, b, (float) (Math.PI / 2), InputPacket.Buttons.DODGE);
        steps(w, 1);
        assertTrue(b.dashTicksLeft > 0, "после 5 секунд уклонение снова доступно");
    }

    @Test
    void dashingBrawlerTakesNoDamage() {
        World w = new World(TICK_RATE);
        Brawler shooter = w.spawnBrawler(1, 0);
        Brawler target = w.spawnBrawler(2, 1);
        target.x = shooter.x;
        target.y = shooter.y + 3f;

        press(w, shooter, (float) (Math.PI / 2), InputPacket.Buttons.ATTACK);
        steps(w, 1); // выстрел
        target.dashTicksLeft = 1000; // держим в рывке, пока снаряд пролетает
        target.dashDx = 0;
        target.dashDy = 0;
        steps(w, 20);
        assertEquals(World.BRAWLER_MAX_HP, target.hp);
    }

    @Test
    void damageChargesSuperAndSuperFiresFan() {
        World w = new World(TICK_RATE);
        Brawler shooter = w.spawnBrawler(1, 0);
        Brawler target = w.spawnBrawler(2, 1);
        float up = (float) (Math.PI / 2);

        for (int hit = 0; hit < 4; hit++) {
            target.x = shooter.x;
            target.y = shooter.y + 3f;
            target.hp = World.BRAWLER_MAX_HP;
            press(w, shooter, up, InputPacket.Buttons.ATTACK);
            steps(w, 20);
        }
        assertEquals(World.SUPER_CHARGE_DAMAGE, shooter.superCharge);
        assertEquals(255, w.ownState(shooter).superCharge());
        assertTrue(hasFlag(w, shooter.id, SnapshotPacket.Flags.SUPER_READY));

        target.x = shooter.x;
        target.y = shooter.y + 4f; // на 4 м попадают 3 центральных снаряда веера
        target.hp = World.BRAWLER_MAX_HP;
        press(w, shooter, up, InputPacket.Buttons.SUPER);
        steps(w, 1);
        assertEquals(0, shooter.superCharge);
        long superShots = w.snapshotEntities().stream()
                .filter(e -> (e.flags() & SnapshotPacket.Flags.SUPER_SHOT) != 0).count();
        assertTrue(superShots > 0);
        steps(w, 20);
        assertTrue(target.hp <= World.BRAWLER_MAX_HP - 3 * ShotType.SUPER.damage || !target.alive,
                "попадает большая часть веера");
        assertEquals(0, shooter.superCharge, "урон супером не заряжает супер");
    }

    @Test
    void shotKeepsAimOfPressEvenIfNextCommandArrivesSameTick() {
        World w = new World(TICK_RATE);
        Brawler b = w.spawnBrawler(1, 0);
        float up = (float) (Math.PI / 2);
        // две команды в одном тике: выстрел вверх, затем «идём вправо» с прицелом вправо
        press(w, b, up, InputPacket.Buttons.ATTACK);
        driver(w).input(b, 127, 0, 0f, 0);
        steps(w, 1);
        var shot = w.snapshotEntities().stream()
                .filter(e -> e.kind() == SnapshotPacket.EntityKind.PROJECTILE).findFirst().orElseThrow();
        assertEquals(Quantize.angleToU16(up), shot.aim());
    }

    private static boolean hasFlag(World w, int id, int flag) {
        return w.snapshotEntities().stream().anyMatch(e -> e.id() == id && (e.flags() & flag) != 0);
    }
}
