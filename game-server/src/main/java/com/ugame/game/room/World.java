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
 * <p>Прямоугольная арена без стен, движение, стрельба снарядами, урон, смерть и возрождение,
 * супер удар и уклонение.
 * <ul>
 *   <li>Супер: заряжается уроном, который наносят обычные выстрелы (SUPER_CHARGE_DAMAGE).
 *   Заряженный супер — веер из SUPER_SHOTS тяжёлых снарядов. После выстрела заряд обнуляется;
 *   смерть заряд не сбрасывает.</li>
 *   <li>Уклонение: рывок на DASH_DISTANCE м за DASH_DURATION_SEC, во время рывка снаряды
 *   пролетают насквозь. Направление — куда идёт игрок, если стоит — куда целится.
 *   Кулдаун DODGE_COOLDOWN_SEC с момента начала рывка.</li>
 * </ul>
 * Логику движения и рывка повторяет предсказание в MatchView.cs — меняете здесь, меняйте и там.
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

    // --- супер удар ---
    static final int SUPER_CHARGE_DAMAGE = 2800;  // столько урона обычными выстрелами = полный заряд (4 попадания)
    static final int SUPER_SHOTS = 5;             // снарядов в веере
    static final float SUPER_SPREAD_RAD = (float) Math.toRadians(40); // ширина веера
    static final float SUPER_SPEED = 18f;
    static final float SUPER_RANGE = 9f;
    static final float SUPER_RADIUS = 0.3f;
    static final int SUPER_DAMAGE = 600;          // за снаряд; весь веер в упор — 3000 = полное здоровье

    // --- уклонение ---
    static final float DODGE_COOLDOWN_SEC = 5f;
    static final float DASH_DURATION_SEC = 0.2f;
    static final float DASH_DISTANCE = 4f;

    /** Лимит сущностей в одном ненадёжном снапшоте (MTU 1200). */
    static final int MAX_SNAPSHOT_ENTITIES = 95; // (1194 − 18) / 12 = 98

    private final int tickRate;
    private final float dt;
    private final int dashTicks;
    private final int dodgeCooldownTicks;
    private final float dashStep;   // м за тик рывка
    private final Map<Integer, Brawler> brawlers = new LinkedHashMap<>();
    private final List<Projectile> projectiles = new ArrayList<>();
    private final int[] teamScore = new int[2];
    private final List<SnapshotPacket.Entity> snapshotBuffer = new ArrayList<>();
    private int nextEntityId = 1;
    private long tick;

    World(int tickRate) {
        this.tickRate = tickRate;
        this.dt = 1f / tickRate;
        this.dashTicks = Math.max(1, Math.round(DASH_DURATION_SEC * tickRate));
        this.dodgeCooldownTicks = Math.round(DODGE_COOLDOWN_SEC * tickRate);
        this.dashStep = DASH_DISTANCE / dashTicks;
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
        // Нажатие запоминается до тика вместе с прицелом ЭТОЙ команды: за один тик может прийти
        // несколько команд (джиттер, повтор после потери пакета), и следующая уже смотрит
        // по движению — без этого выстрел ушёл бы не туда, куда целились.
        if (cmd.pressed(InputPacket.Buttons.ATTACK)) {
            b.attackRequested = true;
            b.attackAim = b.aim;
        }
        if (cmd.pressed(InputPacket.Buttons.SUPER)) {
            b.superRequested = true;
            b.superAim = b.aim;
        }
        if (cmd.pressed(InputPacket.Buttons.DODGE)) {
            b.dodgeRequested = true;
        }
        b.lastInputSeq = cmd.seq();
    }

    void step() {
        tick++;
        for (Brawler b : brawlers.values()) {
            if (b.dodgeCooldownTicks > 0) {
                b.dodgeCooldownTicks--; // кулдаун идёт и пока боец мёртв
            }
            if (!b.alive) {
                if (tick >= b.respawnTick) {
                    respawn(b);
                }
                b.clearRequests();
                continue;
            }
            if (b.dodgeRequested && b.dodgeCooldownTicks == 0 && b.dashTicksLeft == 0) {
                startDash(b);
            }
            moveBrawler(b);
            if (b.attackRequested && tick - b.lastAttackTick >= ATTACK_COOLDOWN_SEC * tickRate) {
                fire(b);
                b.lastAttackTick = tick;
            }
            if (b.superRequested && b.superCharge >= SUPER_CHARGE_DAMAGE) {
                fireSuper(b);
                b.superCharge = 0;
            }
            b.clearRequests();
        }
        stepProjectiles();
    }

    /**
     * Рывок: направление движения, а если игрок стоит — направление прицела. Угол квантуется так же,
     * как в снапшоте, чтобы клиент повторил рывок бит в бит.
     */
    private void startDash(Brawler b) {
        float angle = (Math.abs(b.moveX) > 0.01f || Math.abs(b.moveY) > 0.01f)
                ? (float) Math.atan2(b.moveY, b.moveX)
                : b.aim;
        b.dashAngle = Quantize.angleToU16(angle);
        float a = Quantize.u16ToAngle(b.dashAngle);
        b.dashDx = (float) Math.cos(a);
        b.dashDy = (float) Math.sin(a);
        b.dashTicksLeft = dashTicks;
        b.dodgeCooldownTicks = dodgeCooldownTicks;
    }

    private void moveBrawler(Brawler b) {
        float stepX;
        float stepY;
        if (b.dashTicksLeft > 0) { // во время рывка ввод движения не действует
            stepX = b.dashDx * dashStep;
            stepY = b.dashDy * dashStep;
            b.dashTicksLeft--;
        } else {
            float mx = b.moveX;
            float my = b.moveY;
            float len = (float) Math.sqrt(mx * mx + my * my);
            if (len > 1f) { // диагональ не должна быть быстрее
                mx /= len;
                my /= len;
            }
            stepX = mx * BRAWLER_SPEED * dt;
            stepY = my * BRAWLER_SPEED * dt;
        }
        b.x = clamp(b.x + stepX, BRAWLER_RADIUS, ARENA_WIDTH - BRAWLER_RADIUS);
        b.y = clamp(b.y + stepY, BRAWLER_RADIUS, ARENA_HEIGHT - BRAWLER_RADIUS);
    }

    private void fire(Brawler b) {
        spawnProjectile(b, b.attackAim, false);
    }

    /** Веер из SUPER_SHOTS снарядов, симметрично относительно прицела. */
    private void fireSuper(Brawler b) {
        for (int i = 0; i < SUPER_SHOTS; i++) {
            float t = SUPER_SHOTS == 1 ? 0f : (float) i / (SUPER_SHOTS - 1) - 0.5f; // -0.5..0.5
            spawnProjectile(b, b.superAim + t * SUPER_SPREAD_RAD, true);
        }
    }

    private void spawnProjectile(Brawler b, float angle, boolean isSuper) {
        float speed = isSuper ? SUPER_SPEED : PROJECTILE_SPEED;
        float radius = isSuper ? SUPER_RADIUS : PROJECTILE_RADIUS;
        float dx = (float) Math.cos(angle);
        float dy = (float) Math.sin(angle);
        Projectile p = new Projectile(nextEntityId++, b.id, b.team, isSuper);
        p.x = b.x + dx * (BRAWLER_RADIUS + radius);
        p.y = b.y + dy * (BRAWLER_RADIUS + radius);
        p.vx = dx * speed;
        p.vy = dy * speed;
        p.aim = angle;
        projectiles.add(p);
    }

    private void stepProjectiles() {
        Iterator<Projectile> it = projectiles.iterator();
        outer:
        while (it.hasNext()) {
            Projectile p = it.next();
            float speed = p.isSuper ? SUPER_SPEED : PROJECTILE_SPEED;
            float range = p.isSuper ? SUPER_RANGE : PROJECTILE_RANGE;
            float hitDist = BRAWLER_RADIUS + (p.isSuper ? SUPER_RADIUS : PROJECTILE_RADIUS);
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.traveled += speed * dt;
            if (p.traveled > range
                    || p.x < 0 || p.y < 0 || p.x > ARENA_WIDTH || p.y > ARENA_HEIGHT) {
                it.remove();
                continue;
            }
            for (Brawler target : brawlers.values()) {
                if (!target.alive || target.team == p.team || target.dashTicksLeft > 0) {
                    continue; // в рывке снаряды пролетают насквозь
                }
                float dx = target.x - p.x;
                float dy = target.y - p.y;
                if (dx * dx + dy * dy <= hitDist * hitDist) {
                    int dealt = damage(target, p.ownerId, p.isSuper ? SUPER_DAMAGE : PROJECTILE_DAMAGE);
                    if (!p.isSuper) {
                        chargeSuper(p.ownerId, dealt);
                    }
                    it.remove();
                    continue outer;
                }
            }
        }
    }

    /** Супер копится только от урона обычными выстрелами (иначе супер заряжал бы сам себя). */
    private void chargeSuper(int ownerId, int dealt) {
        Brawler owner = brawlers.get(ownerId);
        if (owner != null) {
            owner.superCharge = Math.min(SUPER_CHARGE_DAMAGE, owner.superCharge + dealt);
        }
    }

    /** Возвращает реально нанесённый урон (не больше оставшегося здоровья). */
    private int damage(Brawler target, int attackerId, int amount) {
        int dealt = Math.min(amount, target.hp);
        target.hp -= amount;
        if (target.hp > 0) {
            return dealt;
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
        return dealt;
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
        b.dashTicksLeft = 0;
    }

    /** Состояние бойца для его владельца: заряд супера, кулдаун и рывок (для предсказания). */
    SnapshotPacket.OwnState ownState(Brawler b) {
        int charge = Math.round(255f * b.superCharge / SUPER_CHARGE_DAMAGE);
        return new SnapshotPacket.OwnState(charge, Math.min(0xFFFF, b.dodgeCooldownTicks),
                Math.min(255, b.dashTicksLeft), b.dashTicksLeft > 0 ? b.dashAngle : 0);
    }

    /** Список сущностей для снапшота. Возвращает переиспользуемый список — не сохраняйте его. */
    List<SnapshotPacket.Entity> snapshotEntities() {
        snapshotBuffer.clear();
        for (Brawler b : brawlers.values()) {
            int hp = b.alive ? Math.max(1, Math.round(255f * b.hp / BRAWLER_MAX_HP)) : 0;
            int flags = 0;
            if (b.alive && b.dashTicksLeft > 0) {
                flags |= SnapshotPacket.Flags.DODGING;
            }
            if (b.superCharge >= SUPER_CHARGE_DAMAGE) {
                flags |= SnapshotPacket.Flags.SUPER_READY;
            }
            snapshotBuffer.add(new SnapshotPacket.Entity(b.id, SnapshotPacket.EntityKind.BRAWLER,
                    Quantize.posToI16(b.x), Quantize.posToI16(b.y), Quantize.angleToU16(b.aim), hp, b.team, flags));
        }
        for (Projectile p : projectiles) {
            if (snapshotBuffer.size() >= MAX_SNAPSHOT_ENTITIES) {
                break; // в реальной игре — область видимости или дельта-сжатие
            }
            snapshotBuffer.add(new SnapshotPacket.Entity(p.id, SnapshotPacket.EntityKind.PROJECTILE,
                    Quantize.posToI16(p.x), Quantize.posToI16(p.y), Quantize.angleToU16(p.aim), 0, p.team,
                    p.isSuper ? SnapshotPacket.Flags.SUPER_SHOT : 0));
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
        boolean superRequested;
        float attackAim;          // прицел команды, в которой нажали атаку
        float superAim;           // прицел команды, в которой нажали супер
        boolean dodgeRequested;
        int superCharge;          // накопленный урон, 0..SUPER_CHARGE_DAMAGE
        int dodgeCooldownTicks;   // 0 — уклонение готово
        int dashTicksLeft;        // > 0 — боец в рывке и неуязвим
        int dashAngle;            // u16
        float dashDx;
        float dashDy;
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

        void clearRequests() {
            attackRequested = false;
            superRequested = false;
            dodgeRequested = false;
        }
    }

    static final class Projectile {
        final int id;
        final int ownerId;
        final int team;
        final boolean isSuper;
        float x;
        float y;
        float vx;
        float vy;
        float aim;
        float traveled;

        Projectile(int id, int ownerId, int team, boolean isSuper) {
            this.id = id;
            this.ownerId = ownerId;
            this.team = team;
            this.isSuper = isSuper;
        }
    }
}
