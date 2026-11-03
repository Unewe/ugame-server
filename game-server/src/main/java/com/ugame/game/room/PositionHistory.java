package com.ugame.game.room;

import java.util.Arrays;

/**
 * Состояния бойца за последние тики — чтобы проверять попадания «в прошлом» (компенсация лагов).
 * Кольцевой буфер по номеру тика. Поток комнаты.
 */
final class PositionHistory {
    private final long[] ticks;
    private final float[] xs;
    private final float[] ys;
    private final int[] lives;        // номер жизни; 0 — боец был мёртв
    private final boolean[] dashing;

    PositionHistory(int size) {
        ticks = new long[size];
        xs = new float[size];
        ys = new float[size];
        lives = new int[size];
        dashing = new boolean[size];
        Arrays.fill(ticks, -1);
    }

    void record(long tick, Brawler b) {
        int i = slot(tick);
        ticks[i] = tick;
        xs[i] = b.x;
        ys[i] = b.y;
        lives[i] = b.alive ? b.life : 0;
        dashing[i] = b.isDashing();
    }

    /**
     * Где был боец в момент {@code at} (дробный тик, позиция интерполируется) → out[0], out[1].
     * Если истории за этот момент нет (боец только появился), out не меняется.
     *
     * @return false — в тот момент попасть в бойца было нельзя: он был в рывке, мёртв или это была
     *         его прошлая жизнь (с тех пор умер и возродился)
     */
    boolean whereWas(double at, int currentLife, float[] out) {
        long t0 = (long) Math.floor(at);
        float f = (float) (at - t0);
        int i0 = index(t0);
        int i1 = index(t0 + 1);
        if (i0 < 0 && i1 < 0) {
            return true;
        }
        if (i0 < 0) { // боец появился между t0 и t0 + 1
            i0 = i1;
            f = 0f;
        } else if (i1 < 0) {
            i1 = i0;
            f = 0f;
        }
        if (!hittable(i0, currentLife) || !hittable(i1, currentLife)) {
            return false;
        }
        out[0] = xs[i0] + (xs[i1] - xs[i0]) * f;
        out[1] = ys[i0] + (ys[i1] - ys[i0]) * f;
        return true;
    }

    private boolean hittable(int i, int currentLife) {
        return lives[i] == currentLife && !dashing[i];
    }

    /** Индекс записи за этот тик или -1, если её нет (слишком давно или боец появился позже). */
    private int index(long tick) {
        int i = slot(tick);
        return ticks[i] == tick ? i : -1;
    }

    private int slot(long tick) {
        return (int) Math.floorMod(tick, (long) ticks.length);
    }
}
