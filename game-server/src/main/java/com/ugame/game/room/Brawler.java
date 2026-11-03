package com.ugame.game.room;

import java.util.ArrayDeque;

/** Боец на арене. Состояние меняет только {@link World} в потоке комнаты. */
final class Brawler {
    final int id;           // 1..255: в снапшоте id владельца снаряда — один байт
    final long playerId;
    final int team;

    // --- положение и здоровье ---
    float x;
    float y;
    float aim;
    float moveX;
    float moveY;
    int hp;
    boolean alive;
    int life;               // номер жизни: растёт при каждом возрождении
    long respawnTick;
    int kills;
    int deaths;

    // --- ввод (см. World.runQueuedInput) ---
    final ArrayDeque<QueuedCommand> inputQueue = new ArrayDeque<>();
    int lastQueuedSeq = -1; // последняя команда, принятая в очередь
    int lastInputSeq = -1;  // последняя ВЫПОЛНЕННАЯ команда (ack для клиента); -1 — ещё не было
    int inputCredit = World.INPUT_CREDIT_MAX;
    int rttMs = -1;         // сглаженный пинг; -1 — не измерен (или игрок отключён)

    // --- стрельба ---
    int lastAttackCmdSeq = -1;
    long lastAttackTick = Long.MIN_VALUE / 2;
    int firedAttackSeq;     // seq последней атаки, которая реально вылетела (для клиента)
    int firedSuperSeq;      // то же для супера
    int superCharge;        // накопленный урон, 0..SUPER_CHARGE_DAMAGE

    // --- уклонение ---
    int dodgeCooldownTicks; // 0 — уклонение готово
    int dashTicksLeft;      // > 0 — боец в рывке и неуязвим
    int dashAngle;          // u16, как в снапшоте
    float dashDx;
    float dashDy;

    final PositionHistory history;

    Brawler(int id, long playerId, int team, int historySize) {
        this.id = id;
        this.playerId = playerId;
        this.team = team;
        this.history = new PositionHistory(historySize);
    }

    boolean isDashing() {
        return alive && dashTicksLeft > 0;
    }
}
