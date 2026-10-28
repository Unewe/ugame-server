package com.ugame.game.room;

import com.ugame.protocol.InputPacket;
import com.ugame.protocol.Quantize;
import com.ugame.protocol.SnapshotPacket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Симуляция матча. Однопоточная: вызывается только из тика своей комнаты.
 *
 * <p>Это заготовка: прямоугольная арена без стен, движение, стрельба снарядами, урон, смерть
 * и возрождение. Сюда добавляются карты с препятствиями, бойцы с разными атаками, супер и т. д.
 * Единицы: метры и секунды; время внутри — в тиках.
 */
final class World {
    static final float ARENA_WIDTH = 20f;
    static final float ARENA_HEIGHT = 30f;

    static final float BRAWLER_RADIUS = 0.5f;
    static final float BRAWLER_SPEED = 5f;      // м/с
    static final int BRAWLER_MAX_HP = 3000;
    static final float ATTACK_COOLDOWN_SEC = 0.5f;
    static final float RESPAWN_SEC = 3f;

    static final float PROJECTILE_SPEED = 15f;  // м/с
    static final float PROJECTILE_RANGE = 8f;   // м
    static final float PROJECTILE_RADIUS = 0.15f;
    static final int PROJECTILE_DAMAGE = 700;

    /** Лимит сущностей в одном ненадёжном снапшоте (MTU 1200). */
    static final int MAX_SNAPSHOT_ENTITIES = 100; // (1194 − 12) / 11 = 107

    private final int tickRate;
    private final float dt;
    private final Map<Integer, Brawler> brawlers = new LinkedHashMap<>();
    private final List<Projectile> projectiles = new ArrayList<>();
    private final int[] teamScore = new int[2];
    private final List<SnapshotPacket.Entity> snapshotBuffer = new ArrayList<>();
    private int nextEntityId = 1;
    private long tick;

    World(int tickRate) {
        this.tickRate = tickRate;
        this.dt = 1f / tickRate;
    }

    long tick() {
        return tick;
    }

    int score(int team) {
        return teamScore[team];
    }

    Iterable<Brawler> brawlers() {
        return brawlers.values();
    }

    Brawler spawnBrawler(long playerId, int team) {
        Brawler b = new Brawler(nextEntityId++, playerId, team & 1);
        respawn(b);
        brawlers.put(b.id, b);
        return b;
    }

    /** Применяет новые команды ввода (уже отфильтрованные по seq), от старой к новой. */
    void applyInput(Brawler b, InputPacket.Command cmd) {
        b.moveX = Quantize.i8ToAxis(cmd.moveX());
        b.moveY = Quantize.i8ToAxis(cmd.moveY());
        b.aim = Quantize.u16ToAngle(cmd.aim());
        if (cmd.pressed(InputPacket.Buttons.ATTACK)) {
            b.attackRequested = true; // не теряем короткое нажатие между тиками
        }
        b.lastInputSeq = cmd.seq();
    }

    void step() {
        tick++;
        for (Brawler b : brawlers.values()) {
            if (!b.alive) {
                if (tick >= b.respawnTick) {
                    respawn(b);
                }
                continue;
            }
            moveBrawler(b);
            if (b.attackRequested && tick - b.lastAttackTick >= ATTACK_COOLDOWN_SEC * tickRate) {
                fire(b);
                b.lastAttackTick = tick;
            }
            b.attackRequested = false;
        }
        stepProjectiles();
    }

    private void moveBrawler(Brawler b) {
        float mx = b.moveX;
        float my = b.moveY;
        float len = (float) Math.sqrt(mx * mx + my * my);
        if (len > 1f) { // диагональ не должна быть быстрее
            mx /= len;
            my /= len;
        }
        b.x = clamp(b.x + mx * BRAWLER_SPEED * dt, BRAWLER_RADIUS, ARENA_WIDTH - BRAWLER_RADIUS);
        b.y = clamp(b.y + my * BRAWLER_SPEED * dt, BRAWLER_RADIUS, ARENA_HEIGHT - BRAWLER_RADIUS);
    }

    private void fire(Brawler b) {
        float dx = (float) Math.cos(b.aim);
        float dy = (float) Math.sin(b.aim);
        Projectile p = new Projectile(nextEntityId++, b.id, b.team);
        p.x = b.x + dx * (BRAWLER_RADIUS + PROJECTILE_RADIUS);
        p.y = b.y + dy * (BRAWLER_RADIUS + PROJECTILE_RADIUS);
        p.vx = dx * PROJECTILE_SPEED;
        p.vy = dy * PROJECTILE_SPEED;
        p.aim = b.aim;
        projectiles.add(p);
    }

    private void stepProjectiles() {
        float step = PROJECTILE_SPEED * dt;
        float hitDist = BRAWLER_RADIUS + PROJECTILE_RADIUS;
        Iterator<Projectile> it = projectiles.iterator();
        outer:
        while (it.hasNext()) {
            Projectile p = it.next();
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.traveled += step;
            if (p.traveled > PROJECTILE_RANGE
                    || p.x < 0 || p.y < 0 || p.x > ARENA_WIDTH || p.y > ARENA_HEIGHT) {
                it.remove();
                continue;
            }
            for (Brawler target : brawlers.values()) {
                if (!target.alive || target.team == p.team) {
                    continue;
                }
                float dx = target.x - p.x;
                float dy = target.y - p.y;
                if (dx * dx + dy * dy <= hitDist * hitDist) {
                    damage(target, p.ownerId, PROJECTILE_DAMAGE);
                    it.remove();
                    continue outer;
                }
            }
        }
    }

    private void damage(Brawler target, int attackerId, int amount) {
        target.hp -= amount;
        if (target.hp > 0) {
            return;
        }
        target.hp = 0;
        target.alive = false;
        target.deaths++;
        target.respawnTick = tick + Math.round(RESPAWN_SEC * tickRate);
        Brawler attacker = brawlers.get(attackerId);
        if (attacker != null) {
            attacker.kills++;
            teamScore[attacker.team]++;
        }
    }

    private void respawn(Brawler b) {
        // команда 0 — снизу, команда 1 — сверху; разносим по x, чтобы не появлялись друг в друге
        float slot = (b.id % 3) - 1; // -1, 0, 1
        b.x = ARENA_WIDTH / 2 + slot * 3f;
        b.y = b.team == 0 ? 2f : ARENA_HEIGHT - 2f;
        b.aim = (float) (b.team == 0 ? Math.PI / 2 : -Math.PI / 2);
        b.hp = BRAWLER_MAX_HP;
        b.alive = true;
        b.moveX = 0;
        b.moveY = 0;
    }

    /** Список сущностей для снапшота. Возвращает переиспользуемый список — не сохраняйте его. */
    List<SnapshotPacket.Entity> snapshotEntities() {
        snapshotBuffer.clear();
        for (Brawler b : brawlers.values()) {
            int hp = b.alive ? Math.max(1, Math.round(255f * b.hp / BRAWLER_MAX_HP)) : 0;
            snapshotBuffer.add(new SnapshotPacket.Entity(b.id, SnapshotPacket.EntityKind.BRAWLER,
                    Quantize.posToI16(b.x), Quantize.posToI16(b.y), Quantize.angleToU16(b.aim), hp, b.team));
        }
        for (Projectile p : projectiles) {
            if (snapshotBuffer.size() >= MAX_SNAPSHOT_ENTITIES) {
                break; // в реальной игре — область видимости или дельта-сжатие
            }
            snapshotBuffer.add(new SnapshotPacket.Entity(p.id, SnapshotPacket.EntityKind.PROJECTILE,
                    Quantize.posToI16(p.x), Quantize.posToI16(p.y), Quantize.angleToU16(p.aim), 0, p.team));
        }
        return snapshotBuffer;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    static final class Brawler {
        final int id;
        final long playerId;
        final int team;
        float x;
        float y;
        float aim;
        float moveX;
        float moveY;
        int hp;
        boolean alive;
        boolean attackRequested;
        long lastAttackTick = Long.MIN_VALUE / 2;
        long respawnTick;
        int lastInputSeq = -1; // -1 = ввода ещё не было
        int kills;
        int deaths;

        Brawler(int id, long playerId, int team) {
            this.id = id;
            this.playerId = playerId;
            this.team = team;
        }
    }

    static final class Projectile {
        final int id;
        final int ownerId;
        final int team;
        float x;
        float y;
        float vx;
        float vy;
        float aim;
        float traveled;

        Projectile(int id, int ownerId, int team) {
            this.id = id;
            this.ownerId = ownerId;
            this.team = team;
        }
    }
}
