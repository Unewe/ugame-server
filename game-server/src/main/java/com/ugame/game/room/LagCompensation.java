package com.ugame.game.room;

import com.ugame.protocol.InputPacket;

/**
 * Компенсация лагов: на сколько тиков «отмотать» мир, проверяя попадания выстрела.
 *
 * <p>Стрелок целится во врагов, которых видит в прошлом: снапшот идёт к нему RTT/2, клиент
 * показывает чужих с задержкой интерполяции, и ещё RTT/2 летит его ввод. Свои выстрелы клиент
 * рисует сразу, поэтому на его экране снаряд встречается с врагами, отстающими на это время.
 * Сервер повторяет ровно это: проверяет попадание по позиции цели в том тике, который стрелок
 * видел на экране (см. {@link PositionHistory}).
 *
 * <p>Отмотка фиксируется в момент выстрела и не больше maxRewind: при огромном пинге стрелку
 * придётся брать упреждение, зато цель не получает урон «из-за угла».
 */
final class LagCompensation {
    /** Задержка показа чужих сущностей на клиенте, тиков (MatchView.InterpDelayTicks). */
    static final int DEFAULT_INTERP_DELAY_TICKS = 3;
    /** Дальше в прошлое не отматываем (пинг ~350 мс). 0 — компенсация выключена. */
    static final int DEFAULT_MAX_REWIND_MS = 500;
    /** Запас сверх «RTT + интерполяция + очередь» для тика, присланного клиентом (джиттер). */
    static final float SLACK_SEC = 0.15f;

    private final int tickRate;
    private final float interpDelayTicks;
    private final float maxRewindTicks;
    private final float slackTicks;

    LagCompensation(int tickRate, int interpDelayTicks, int maxRewindMs) {
        this.tickRate = tickRate;
        this.interpDelayTicks = Math.max(0, interpDelayTicks);
        this.maxRewindTicks = Math.max(0f, maxRewindMs * tickRate / 1000f);
        this.slackTicks = SLACK_SEC * tickRate;
    }

    /** Сколько тиков истории позиций нужно хранить. */
    int historySize() {
        return (int) Math.ceil(maxRewindTicks) + 2;
    }

    /**
     * Отмотка для выстрела командой {@code q}, выполненной в тике {@code tick}.
     *
     * <p>Клиент присылает тик, в котором показывал врагов в момент выстрела (view). Его снаряд
     * в возрасте a встречает на экране врагов в тике view + a. Серверный снаряд в конце тика T0 + k
     * имеет возраст k + 1 тиков, значит, цель надо брать в тике view + k + 1, то есть
     * отмотка = T0 − view − 1 — постоянная на весь полёт. Ожидание в очереди, колебания пинга
     * и сдвиги часов клиента сюда входят сами — оценивать ничего не нужно.
     *
     * <p>Подделать «вид из далёкого прошлого» нельзя: отмотка не больше того, что объясняет пинг
     * игрока, плюс {@link #SLACK_SEC} на джиттер. Без тика (первые снапшоты, боты) — оценка по пингу.
     *
     * @param rttMs сглаженный пинг стрелка; -1 — ещё не измерен
     */
    float shotRewind(long tick, int rttMs, QueuedCommand q) {
        if (maxRewindTicks <= 0f) {
            return 0f;
        }
        float queuedTicks = Math.max(0, tick - q.receivedTick() - 1);
        float byPing = rttMs < 0 ? -1f : rttMs * tickRate / 1000f + interpDelayTicks + queuedTicks;
        InputPacket.Command cmd = q.cmd();
        float rewind;
        if (cmd.hasViewTick()) {
            int back = ((int) (tick & 0xFFFF) - cmd.viewTick()) & 0xFFFF;
            rewind = back >= 0x8000 ? 0f : back - cmd.viewFrac() / 256f - 1f; // «из будущего» — мусор
            if (byPing >= 0f) {
                rewind = Math.min(rewind, byPing + slackTicks);
            }
        } else {
            rewind = Math.max(0f, byPing);
        }
        return Math.max(0f, Math.min(maxRewindTicks, rewind));
    }
}
