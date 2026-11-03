package com.ugame.game.room;

/**
 * Параметры снаряда. Те же числа — в MatchView.cs на клиенте (предсказание своих выстрелов):
 * меняете здесь — меняйте и там.
 */
enum ShotType {
    /** Обычная атака. */
    NORMAL(15f, 8f, 0.15f, 700),
    /** Снаряд супер удара; весь веер в упор — 5 × 600 = 3000, полное здоровье. */
    SUPER(18f, 9f, 0.3f, 600);

    final float speed;   // м/с
    final float range;   // м
    final float radius;  // м
    final int damage;

    ShotType(float speed, float range, float radius, int damage) {
        this.speed = speed;
        this.range = range;
        this.radius = radius;
        this.damage = damage;
    }

    /** Расстояние между центрами снаряда и бойца, при котором это попадание. */
    float hitDistance() {
        return World.BRAWLER_RADIUS + radius;
    }
}
