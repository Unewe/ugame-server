package com.ugame.game.room;

import com.ugame.protocol.InputPacket;
import com.ugame.protocol.Quantize;
import java.util.HashMap;
import java.util.Map;

/**
 * Клиенты в тестах. Как настоящий клиент, каждый живой боец шлёт команду на каждый тик: если тест
 * не задал команду явно, уходит «удержание» — прошлое движение и прицел без кнопок.
 */
final class WorldDriver {
    final World w;
    private final Map<World.Brawler, Integer> seq = new HashMap<>();
    private final Map<World.Brawler, InputPacket.Command> held = new HashMap<>();

    WorldDriver(World w) {
        this.w = w;
    }

    /** Команда бойца; кнопки действуют только в ней, движение и прицел удерживаются дальше. */
    InputPacket.Command input(World.Brawler b, int moveX, int moveY, float aim, int buttons) {
        return queue(b, moveX, moveY, Quantize.angleToU16(aim), buttons);
    }

    /** Команда с тиком, который клиент показывал на экране (как шлёт MatchView). */
    InputPacket.Command inputViewing(World.Brawler b, float aim, int buttons, double viewTick) {
        long whole = (long) Math.floor(viewTick);
        int frac = (int) ((viewTick - whole) * 256);
        return queue(b, 0, 0, Quantize.angleToU16(aim), buttons, (int) (whole & 0xFFFF), frac);
    }

    private InputPacket.Command queue(World.Brawler b, int moveX, int moveY, int aimU16, int buttons) {
        return queue(b, moveX, moveY, aimU16, buttons, 0, 0);
    }

    private InputPacket.Command queue(World.Brawler b, int moveX, int moveY, int aimU16, int buttons,
                                      int viewTick, int viewFrac) {
        int s = (seq.getOrDefault(b, 0) + 1) & 0xFFFF;
        seq.put(b, s);
        InputPacket.Command cmd = new InputPacket.Command(s, moveX, moveY, aimU16, buttons, viewTick, viewFrac);
        w.queueInput(b, cmd);
        held.put(b, cmd);
        return cmd;
    }

    int seq(World.Brawler b) {
        return seq.getOrDefault(b, 0);
    }

    /** Следующая команда бойца получит seq = value + 1. */
    void setSeq(World.Brawler b, int value) {
        seq.put(b, value & 0xFFFF);
    }

    void steps(int n) {
        for (int i = 0; i < n; i++) {
            for (World.Brawler b : w.brawlers()) {
                if (b.alive && b.inputQueue.isEmpty()) {
                    InputPacket.Command h = held.get(b);
                    if (h == null) {
                        queue(b, 0, 0, Quantize.angleToU16(b.aim), 0);
                    } else {
                        queue(b, h.moveX(), h.moveY(), h.aim(), 0);
                    }
                }
            }
            w.step();
        }
    }
}
