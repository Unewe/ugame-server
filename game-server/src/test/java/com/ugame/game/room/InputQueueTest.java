package com.ugame.game.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ugame.protocol.InputPacket;
import com.ugame.protocol.Quantize;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Очередь ввода: результат зависит только от команд, а не от того, как сеть их доставила. */
class InputQueueTest {
    private static final int TICK_RATE = 30;

    /** Клиент идёт по диагонали, меняет направление, делает рывок и стреляет — 60 команд. */
    private static List<InputPacket.Command> clientCommands() {
        List<InputPacket.Command> list = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            int mx = i < 30 ? 127 : -90;
            int my = i < 20 ? 60 : 127;
            int buttons = 0;
            if (i == 25) {
                buttons |= InputPacket.Buttons.DODGE;
            }
            if (i % 16 == 0) {
                buttons |= InputPacket.Buttons.ATTACK;
            }
            list.add(new InputPacket.Command(i, mx, my, Quantize.angleToU16(0.3f * i), buttons));
        }
        return list;
    }

    /** Прогоняет команды, доставляя их пачками arrivals[i] штук перед i-м тиком. */
    private static World.Brawler play(int[] arrivals) {
        World w = new World(TICK_RATE);
        World.Brawler b = w.spawnBrawler(1, 0);
        List<InputPacket.Command> cmds = clientCommands();
        int next = 0;
        for (int t = 0; t < 200; t++) {
            int count = t < arrivals.length ? arrivals[t] : 1;
            for (int k = 0; k < count && next < cmds.size(); k++) {
                w.queueInput(b, cmds.get(next++));
            }
            w.step();
        }
        return b;
    }

    @Test
    void jitterDoesNotChangeTheOutcome() {
        World.Brawler smooth = play(new int[] {1});
        // провалы и пачки, повторы старых команд — как в реальной сети с джиттером
        World.Brawler jittery = play(new int[] {1, 0, 0, 3, 1, 0, 2, 2, 0, 0, 0, 4, 1, 1, 0, 3, 0, 2});
        assertEquals(60, smooth.lastInputSeq);
        assertEquals(60, jittery.lastInputSeq);
        assertEquals(smooth.x, jittery.x, 0f);
        assertEquals(smooth.y, jittery.y, 0f);
        assertEquals(smooth.dodgeCooldownTicks, jittery.dodgeCooldownTicks);
        assertEquals(smooth.firedAttackSeq, jittery.firedAttackSeq);
    }

    @Test
    void duplicatesAndOldCommandsAreIgnored() {
        World w = new World(TICK_RATE);
        World.Brawler b = w.spawnBrawler(1, 0);
        var c1 = new InputPacket.Command(1, 127, 0, 0, 0);
        var c2 = new InputPacket.Command(2, 127, 0, 0, 0);
        w.queueInput(b, c1);
        w.queueInput(b, c2);
        w.queueInput(b, c1); // пакет с теми же командами пришёл ещё раз
        w.queueInput(b, c2);
        float x0 = b.x;
        for (int i = 0; i < 5; i++) {
            w.step();
        }
        assertEquals(2, b.lastInputSeq);
        assertEquals(x0 + 2 * World.BRAWLER_SPEED / TICK_RATE, b.x, 1e-4);
    }

    @Test
    void noInputMeansStandingStill() {
        World w = new World(TICK_RATE);
        World.Brawler b = w.spawnBrawler(1, 0);
        w.queueInput(b, new InputPacket.Command(1, 127, 0, 0, 0));
        float x0 = b.x;
        for (int i = 0; i < 30; i++) {
            w.step();
        }
        assertEquals(x0 + World.BRAWLER_SPEED / TICK_RATE, b.x, 1e-4,
                "одна команда — один шаг; сервер не идёт дальше по старому вводу (игрок отключился)");
    }

    @Test
    void sendingCommandsTooFastDoesNotMakeYouFaster() {
        World w = new World(TICK_RATE);
        World.Brawler b = w.spawnBrawler(1, 0);
        b.x = 1f;
        int seq = 0;
        for (int t = 0; t < 60; t++) {
            for (int k = 0; k < 8; k++) { // спидхак: 8 команд на каждый тик сервера
                w.queueInput(b, new InputPacket.Command(++seq, 127, 0, 0, 0));
            }
            w.step();
        }
        float maxSteps = 60 + World.INPUT_CREDIT_MAX;
        assertTrue(b.x <= 1f + maxSteps * World.BRAWLER_SPEED / TICK_RATE + 1e-3, "x = " + b.x);
    }

    @Test
    void backlogIsCaughtUpQuickly() {
        World w = new World(TICK_RATE);
        World.Brawler b = w.spawnBrawler(1, 0);
        for (int i = 1; i <= 6; i++) { // после провала связи пришло 6 команд разом
            w.queueInput(b, new InputPacket.Command(i, 127, 0, 0, 0));
        }
        w.step();
        w.step();
        assertTrue(b.lastInputSeq >= 4, "догоняем по две за тик, ack = " + b.lastInputSeq);
    }

    @Test
    void commandsSentWhileDeadAreOnlyAcked() {
        World w = new World(TICK_RATE);
        World.Brawler b = w.spawnBrawler(1, 0);
        b.alive = false;
        b.respawnTick = Long.MAX_VALUE;
        float x0 = b.x;
        w.queueInput(b, new InputPacket.Command(1, 127, 0, 0, InputPacket.Buttons.ATTACK));
        w.queueInput(b, new InputPacket.Command(2, 127, 0, 0, 0));
        w.step();
        assertEquals(2, b.lastInputSeq);
        assertEquals(x0, b.x, 0f);
        assertEquals(0, b.firedAttackSeq);
    }

    @Test
    void reconnectRestartsCommandNumbering() {
        World w = new World(TICK_RATE);
        World.Brawler b = w.spawnBrawler(1, 0);
        for (int i = 1; i <= 40; i++) {
            w.queueInput(b, new InputPacket.Command(1000 + i, 0, 0, 0, 0));
            w.step();
        }
        w.resetInput(b);
        float x0 = b.x;
        w.queueInput(b, new InputPacket.Command(1, 127, 0, 0, 0)); // новое подключение начинает с 1
        w.step();
        assertEquals(1, b.lastInputSeq);
        assertTrue(b.x > x0);
    }
}
