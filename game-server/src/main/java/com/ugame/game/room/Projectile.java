package com.ugame.game.room;

/** Летящий снаряд. Поток комнаты. */
final class Projectile {
    final int id;
    final int ownerId;
    final int team;
    final ShotType type;
    /** На сколько тиков в прошлое смотреть, проверяя попадания (фиксируется в момент выстрела). */
    final float rewindTicks;

    float x;
    float y;
    float vx;
    float vy;
    float aim;
    float traveled;

    Projectile(int id, int ownerId, int team, ShotType type, float rewindTicks) {
        this.id = id;
        this.ownerId = ownerId;
        this.team = team;
        this.type = type;
        this.rewindTicks = rewindTicks;
    }
}
