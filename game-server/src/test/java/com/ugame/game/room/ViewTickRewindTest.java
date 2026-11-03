package com.ugame.game.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ugame.protocol.InputPacket;
import org.junit.jupiter.api.Test;

/** Отмотка по тику, который клиент показывал на экране, и проверка попадания по отрезку полёта. */
class ViewTickRewindTest {
    private static final int TICK_RATE = 30;
    private static final float UP = (float) (Math.PI / 2);

    private World w;
    private WorldDriver d;
    private Brawler shooter;
    private Brawler target;

    private void setUp(float targetX, float targetY) {
        w = new World(TICK_RATE, 3, 500);
        d = new WorldDriver(w);
        shooter = w.spawnBrawler(1, 0);
        target = w.spawnBrawler(2, 1);
        shooter.x = 10f;
        shooter.y = 2f;
        target.x = targetX;
        target.y = targetY;
    }

    /**
     * Цель бежит вправо и пересекает линию огня в тике tv. Стрелок видел картинку с отставанием
     * (пинг + интерполяция) и выстрелил так, что на его экране снаряд встретил цель. Сервер,
     * проверяя по присланному тику, засчитывает именно это попадание.
     */
    @Test
    void shotIsCheckedAgainstWhatTheClientSaw() {
        setUp(7f, 6f);
        d.input(target, 127, 0, -UP, 0);
        while (target.x < 10f) {
            d.steps(1);
        }
        long tv = w.tick();       // цель на линии огня
        d.steps(5);               // к серверу выстрел приходит позже: цель уже ушла на ~0,8 м
        // снаряд долетает до y = 6 примерно за 7 тиков — значит, стрелок жал «огонь», видя тик tv − 7
        d.inputViewing(shooter, UP, InputPacket.Buttons.ATTACK, tv - 7 + 0.5);
        d.steps(20);
        assertEquals(World.BRAWLER_MAX_HP - ShotType.NORMAL.damage, target.hp);
    }

    @Test
    void sameShotWithoutCompensationMisses() {
        setUp(7f, 6f);
        d.input(target, 127, 0, -UP, 0);
        while (target.x < 10f) {
            d.steps(1);
        }
        d.steps(5);
        d.input(shooter, 0, 0, UP, InputPacket.Buttons.ATTACK); // ни тика, ни RTT — без отмотки
        d.steps(20);
        assertEquals(World.BRAWLER_MAX_HP, target.hp);
    }

    @Test
    void forgedOldViewTickIsLimitedByPing() {
        setUp(10f, 6f);
        d.steps(30);
        w.setRtt(shooter, 50); // 1,5 тика + 3 интерполяции + 4,5 запаса = 9 тиков максимум
        var cmd = new InputPacket.Command(1, 0, 0, 0, InputPacket.Buttons.ATTACK,
                (int) ((w.tick() - 14) & 0xFFFF), 0);
        float rewind = w.shotRewind(shooter, new QueuedCommand(cmd, w.tick() - 1));
        assertEquals(9f, rewind, 0.01f);

        var future = new InputPacket.Command(1, 0, 0, 0, InputPacket.Buttons.ATTACK,
                (int) ((w.tick() + 5) & 0xFFFF), 0);
        assertEquals(0f, w.shotRewind(shooter, new QueuedCommand(future, w.tick() - 1)), 0f);
    }

    @Test
    void viewTickIsUsedExactlyWhenPingAllowsIt() {
        setUp(10f, 6f);
        d.steps(30);
        w.setRtt(shooter, 200); // 6 + 3 + 4,5 = 13,5 тиков разрешено
        var cmd = new InputPacket.Command(1, 0, 0, 0, InputPacket.Buttons.ATTACK,
                (int) ((w.tick() - 11) & 0xFFFF), 128);
        // T0 − view − 1 = 11 − 0,5 − 1
        assertEquals(9.5f, w.shotRewind(shooter, new QueuedCommand(cmd, w.tick() - 1)), 0.01f);
    }

    /**
     * Снаряд проходит 0,5 м за тик. Цель стоит сбоку от линии на 0,62 м (радиусы — 0,65), между
     * точками конца тиков: проверка «по точкам» промахивалась, хотя на экране снаряд её задевал.
     */
    @Test
    void grazingHitBetweenTickPointsCounts() {
        setUp(10.62f, 5.9f);
        d.steps(5);
        d.input(shooter, 0, 0, UP, InputPacket.Buttons.ATTACK);
        d.steps(20);
        assertEquals(World.BRAWLER_MAX_HP - ShotType.NORMAL.damage, target.hp);
    }

    @Test
    void segmentDistance() {
        assertEquals(0.25f, World.segmentDistSq(0, 0, 2, 0, 1, 0.5f), 1e-6);
        assertEquals(1f, World.segmentDistSq(0, 0, 2, 0, 3, 0), 1e-6);   // за концом отрезка
        assertTrue(World.segmentDistSq(0, 0, 0, 0, 1, 1) > 1.99f);        // вырожденный отрезок
    }
}
