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
 * <p>Прямоугольная арена без стен: движение, стрельба снарядами ({@link ShotType}), урон, смерть
 * и возрождение, супер удар и уклонение.
 * <ul>
 *   <li>Супер заряжается уроном обычных выстрелов (SUPER_CHARGE_DAMAGE) и выпускает веер из
 *   SUPER_SHOTS снарядов. После выстрела заряд обнуляется; смерть его не сбрасывает.</li>
 *   <li>Уклонение — рывок на DASH_DISTANCE м за DASH_DURATION_SEC, во время рывка снаряды
 *   пролетают насквозь. Направление — куда идёт игрок, если стоит — куда целится.</li>
 * </ul>
 *
 * <p><b>Ввод.</b> Команды клиента (одна на его тик) встают в очередь бойца и выполняются по одной
 * за тик сервера — тем же шагом, что предсказание в MatchView. Поэтому позиция после команды N
 * у сервера и у клиента совпадает, и своему бойцу не нужны поправки. Кулдаун атаки тоже считается
 * в командах: клиент точно знает, какой выстрел сервер примет, и показывает его сразу.
 *
 * <p><b>Попадания</b> проверяются «в прошлом» — по тому, что стрелок видел на экране
 * (см. {@link LagCompensation}). Рывок честен для уклоняющегося: цель в рывке сейчас или
 * в отмотанный момент не получает урона.
 *
 * <p><b>Снаряды в снапшоте</b> — не позициями, а событиями «выстрел» и «попадание»: полёт по прямой
 * клиент считает сам. События хранятся SHOT_EVENT_KEEP_TICKS, и каждый клиент получает их,
 * пока не подтвердит (см. {@link #shotEventsAfter}).
 *
 * <p>Движение, рывок и стрельбу повторяет предсказание в MatchView.cs — меняете здесь, меняйте
 * и там. Единицы: метры и секунды; время внутри — в тиках.
 */
final class World {
    // --- арена и боец ---
    static final float ARENA_WIDTH = 20f;
    static final float ARENA_HEIGHT = 30f;
    static final float BRAWLER_RADIUS = 0.5f;
    static final float BRAWLER_SPEED = 5f;        // м/с
    static final int BRAWLER_MAX_HP = 3000;
    static final float RESPAWN_SEC = 3f;

    // --- атака ---
    static final float ATTACK_COOLDOWN_SEC = 0.5f;
    /** Доля кулдауна, на которую тики сервера могут «сжаться» из-за джиттера сети. */
    static final float ATTACK_JITTER_SLACK = 1f / 3f;

    // --- супер удар ---
    static final int SUPER_CHARGE_DAMAGE = 2800;  // столько урона обычными выстрелами = полный заряд
    static final int SUPER_SHOTS = 5;
    static final float SUPER_SPREAD_RAD = (float) Math.toRadians(40);

    // --- уклонение ---
    static final float DODGE_COOLDOWN_SEC = 5f;
    static final float DASH_DURATION_SEC = 0.2f;
    static final float DASH_DISTANCE = 4f;

    // --- id сущностей ---
    static final int MAX_BRAWLER_ID = 255;        // id владельца снаряда в снапшоте — один байт
    static final int FIRST_PROJECTILE_ID = 256;
    static final int MAX_PROJECTILE_ID = 0xFFFF;  // дальше id снарядов идут по кругу

    // --- очередь ввода (см. runQueuedInput) ---
    static final int INPUT_QUEUE_TARGET = 2;      // столько команд держим «в запасе» от джиттера
    static final int INPUT_CREDIT_MAX = 4;        // столько команд сверх «одна за тик» можно догнать
    static final int MAX_INPUT_QUEUE = 8;         // ≈ 270 мс при 30 Гц

    /** Лимит бойцов в снапшоте; остальное место в пакете — под события снарядов. */
    static final int MAX_SNAPSHOT_ENTITIES = 64;
    /**
     * Сколько тиков хранить события снарядов (2 с). Больше, чем живёт снаряд, — поэтому новый
     * или переподключившийся клиент получает выстрелы всех летящих снарядов.
     */
    static final int SHOT_EVENT_KEEP_TICKS = 60;

    private final int tickRate;
    private final float dt;
    private final int dashTicks;
    private final float dashStep;                 // м за тик рывка
    private final int dodgeCooldownTicks;
    private final int attackCooldownTicks;
    private final int attackMinTicks;             // минимум тиков сервера между выстрелами (анти-спидхак)
    private final LagCompensation lagCompensation;

    private final Map<Integer, Brawler> brawlers = new LinkedHashMap<>();
    private final List<Projectile> projectiles = new ArrayList<>();
    private final int[] teamScore = new int[2];
    private final List<SnapshotPacket.Entity> snapshotBuffer = new ArrayList<>();
    private final List<SnapshotPacket.ShotSpawn> shotSpawns = new ArrayList<>(); // от старых к новым
    private final List<SnapshotPacket.ShotHit> shotHits = new ArrayList<>();
    private final float[] pastPos = new float[2]; // результат PositionHistory.whereWas без выделений
    private int nextBrawlerId = 1;
    private int nextProjectileId = FIRST_PROJECTILE_ID;
    private long tick;

    World(int tickRate) {
        this(tickRate, LagCompensation.DEFAULT_INTERP_DELAY_TICKS, LagCompensation.DEFAULT_MAX_REWIND_MS);
    }

    /**
     * @param interpDelayTicks на сколько тиков клиент показывает чужих в прошлом (MatchView.InterpDelayTicks)
     * @param maxRewindMs      предел отмотки при проверке попаданий; 0 — без компенсации лагов
     */
    World(int tickRate, int interpDelayTicks, int maxRewindMs) {
        this.tickRate = tickRate;
        this.dt = 1f / tickRate;
        this.dashTicks = Math.max(1, Math.round(DASH_DURATION_SEC * tickRate));
        this.dashStep = DASH_DISTANCE / dashTicks;
        this.dodgeCooldownTicks = Math.round(DODGE_COOLDOWN_SEC * tickRate);
        this.attackCooldownTicks = Math.max(1, Math.round(ATTACK_COOLDOWN_SEC * tickRate));
        this.attackMinTicks = Math.max(1, Math.round(attackCooldownTicks * (1f - ATTACK_JITTER_SLACK)));
        this.lagCompensation = new LagCompensation(tickRate, interpDelayTicks, maxRewindMs);
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

    Iterable<Projectile> projectiles() {
        return projectiles;
    }

    Brawler spawnBrawler(long playerId, int team) {
        if (nextBrawlerId > MAX_BRAWLER_ID) {
            throw new IllegalStateException("too many brawlers in one match");
        }
        Brawler b = new Brawler(nextBrawlerId++, playerId, team & 1, lagCompensation.historySize());
        respawn(b);
        brawlers.put(b.id, b);
        return b;
    }

    /** Сглаженный пинг игрока, мс; -1 — ещё не измерен или игрок отключился. */
    void setRtt(Brawler b, int rttMs) {
        b.rttMs = rttMs;
    }

    /** Отмотка для выстрела этой командой, если выполнить её сейчас (см. {@link LagCompensation}). */
    float shotRewind(Brawler b, QueuedCommand q) {
        return lagCompensation.shotRewind(tick, b.rttMs, q);
    }

    // ------------------------------------------------------------------ ввод

    /**
     * Ставит команду в очередь бойца. Дубли и устаревшие (пакет содержит несколько последних
     * команд, UDP может переставить пакеты) отбрасываются.
     */
    void queueInput(Brawler b, InputPacket.Command cmd) {
        if (b.lastQueuedSeq >= 0 && !Quantize.seqGreater(cmd.seq(), b.lastQueuedSeq)) {
            return;
        }
        b.lastQueuedSeq = cmd.seq();
        if (b.inputQueue.size() >= MAX_INPUT_QUEUE) {
            b.inputQueue.pollFirst(); // честный клиент сюда не доходит: столько не накопить без спидхака
        }
        b.inputQueue.addLast(new QueuedCommand(cmd, tick));
    }

    /**
     * Игрок вошёл заново: новое подключение нумерует команды с начала. Боец остаётся тем же,
     * но всё, что привязано к seq старого подключения, сбрасываем.
     */
    void resetInput(Brawler b) {
        b.inputQueue.clear();
        b.lastQueuedSeq = -1;
        b.lastInputSeq = -1;
        b.lastAttackCmdSeq = -1;
        b.firedAttackSeq = 0;
        b.firedSuperSeq = 0;
    }

    // ------------------------------------------------------------------ тик

    void step() {
        tick++;
        forgetOldShotEvents();
        for (Brawler b : brawlers.values()) {
            if (b.alive) {
                // Двигается только по командам. Нет команд (лаг, игрок отключился) — стоит,
                // а не идёт дальше по последнему вводу, которого клиент не предсказывал.
                runQueuedInput(b);
            } else {
                stepDead(b);
            }
        }
        for (Brawler b : brawlers.values()) {
            b.history.record(tick, b); // то же состояние, что уйдёт в снапшот этого тика
        }
        stepProjectiles();
    }

    private void stepDead(Brawler b) {
        if (b.dodgeCooldownTicks > 0) {
            b.dodgeCooldownTicks--; // пока боец мёртв, кулдаун идёт по тикам
        }
        // команды, отправленные до того, как клиент узнал о смерти, только подтверждаем
        for (QueuedCommand q; (q = b.inputQueue.pollFirst()) != null; ) {
            b.lastInputSeq = q.cmd().seq();
        }
        if (tick >= b.respawnTick) {
            respawn(b);
        }
    }

    /**
     * Обычно одна команда за тик. Если очередь выросла (после джиттера пришла пачка) — две, пока не
     * вернёмся к INPUT_QUEUE_TARGET: команда «в запасе» сглаживает следующий провал. Кредит
     * (+1 за тик, не больше INPUT_CREDIT_MAX) не даёт слишком частым командам ускорить бойца.
     */
    private void runQueuedInput(Brawler b) {
        b.inputCredit = Math.min(INPUT_CREDIT_MAX, b.inputCredit + 1);
        int budget = b.inputQueue.size() > INPUT_QUEUE_TARGET ? 2 : 1;
        for (int n = 0; n < budget && b.inputCredit > 0 && !b.inputQueue.isEmpty(); n++) {
            b.inputCredit--;
            runCommand(b, b.inputQueue.pollFirst());
        }
    }

    /**
     * Один шаг бойца по команде — то же, что MatchView.Step на клиенте (кулдаун рывка, рывок,
     * движение), плюс выстрелы.
     */
    private void runCommand(Brawler b, QueuedCommand q) {
        InputPacket.Command cmd = q.cmd();
        b.moveX = Quantize.i8ToAxis(cmd.moveX());
        b.moveY = Quantize.i8ToAxis(cmd.moveY());
        b.aim = Quantize.u16ToAngle(cmd.aim());
        b.lastInputSeq = cmd.seq();

        if (b.dodgeCooldownTicks > 0) {
            b.dodgeCooldownTicks--;
        }
        if (cmd.pressed(InputPacket.Buttons.DODGE) && b.dodgeCooldownTicks == 0 && b.dashTicksLeft == 0) {
            startDash(b);
        }
        moveBrawler(b);

        boolean attack = cmd.pressed(InputPacket.Buttons.ATTACK) && attackReady(b, cmd.seq());
        boolean superShot = cmd.pressed(InputPacket.Buttons.SUPER) && b.superCharge >= SUPER_CHARGE_DAMAGE;
        if (!attack && !superShot) {
            return;
        }
        float rewind = shotRewind(b, q);
        if (attack) {
            b.lastAttackCmdSeq = cmd.seq();
            b.lastAttackTick = tick;
            b.firedAttackSeq = cmd.seq();
            spawnProjectile(b, b.aim, ShotType.NORMAL, rewind);
        }
        if (superShot) {
            b.superCharge = 0;
            b.firedSuperSeq = cmd.seq();
            for (int i = 0; i < SUPER_SHOTS; i++) { // веер симметрично относительно прицела
                float t = SUPER_SHOTS == 1 ? 0f : (float) i / (SUPER_SHOTS - 1) - 0.5f; // -0.5..0.5
                spawnProjectile(b, b.aim + t * SUPER_SPREAD_RAD, ShotType.SUPER, rewind);
            }
        }
    }

    /**
     * Кулдаун атаки — в командах клиента (одна команда = один его тик), как в предсказании. Плюс
     * нижняя граница в тиках сервера: команды могут прийти пачкой, но не чаще attackMinTicks.
     */
    private boolean attackReady(Brawler b, int seq) {
        boolean bySeq = b.lastAttackCmdSeq < 0 || ((seq - b.lastAttackCmdSeq) & 0xFFFF) >= attackCooldownTicks;
        return bySeq && tick - b.lastAttackTick >= attackMinTicks;
    }

    // ------------------------------------------------------------------ движение

    /** Угол рывка квантуется так же, как в снапшоте, чтобы клиент повторил рывок бит в бит. */
    private void startDash(Brawler b) {
        boolean moving = Math.abs(b.moveX) > 0.01f || Math.abs(b.moveY) > 0.01f;
        float angle = moving ? (float) Math.atan2(b.moveY, b.moveX) : b.aim;
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

    // ------------------------------------------------------------------ снаряды

    private void spawnProjectile(Brawler b, float angle, ShotType type, float rewind) {
        float dx = (float) Math.cos(angle);
        float dy = (float) Math.sin(angle);
        Projectile p = new Projectile(nextProjectileId(), b.id, b.team, type, rewind);
        p.x = b.x + dx * type.hitDistance();
        p.y = b.y + dy * type.hitDistance();
        p.vx = dx * type.speed;
        p.vy = dy * type.speed;
        projectiles.add(p);
        shotSpawns.add(new SnapshotPacket.ShotSpawn(p.id, tick, Quantize.posToI16(p.x), Quantize.posToI16(p.y),
                Quantize.angleToU16(angle), b.id, b.team,
                type == ShotType.SUPER ? SnapshotPacket.ShotKind.SUPER : SnapshotPacket.ShotKind.NORMAL));
    }

    private int nextProjectileId() {
        int id = nextProjectileId;
        nextProjectileId = id >= MAX_PROJECTILE_ID ? FIRST_PROJECTILE_ID : id + 1;
        return id;
    }

    /**
     * Попадание проверяется по всему отрезку полёта за тик, а не по конечной точке: снаряд проходит
     * 0,5–0,6 м за тик, и «скользящее» попадание, которое клиент видит покадрово, между точками
     * терялось бы. Отрезок обрезан по дальности.
     */
    private void stepProjectiles() {
        for (Iterator<Projectile> it = projectiles.iterator(); it.hasNext(); ) {
            Projectile p = it.next();
            ShotType type = p.type;
            float x0 = p.x;
            float y0 = p.y;
            float inRange = Math.min(1f, (type.range - p.traveled) / (type.speed * dt)); // доля шага до конца дальности
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.traveled += type.speed * dt;

            Brawler target = findHit(p, x0, y0, x0 + (p.x - x0) * inRange, y0 + (p.y - y0) * inRange);
            if (target != null) {
                int dealt = damage(target, p.ownerId, type.damage);
                if (type == ShotType.NORMAL) {
                    chargeSuper(p.ownerId, dealt); // супер от супера не заряжается
                }
                shotHits.add(new SnapshotPacket.ShotHit(p.id, tick));
                it.remove();
            } else if (p.traveled >= type.range || !insideArena(p.x, p.y)) {
                it.remove(); // конец полёта клиент считает сам — событие не нужно
            }
        }
    }

    /** Первый враг, в которого снаряд попал на отрезке (x0, y0)–(x1, y1), по тому, что видел стрелок. */
    private Brawler findHit(Projectile p, float x0, float y0, float x1, float y1) {
        float hitDist = p.type.hitDistance();
        for (Brawler target : brawlers.values()) {
            if (!target.alive || target.team == p.team || target.isDashing()) {
                continue; // мёртвых не бьём; в рывке снаряды пролетают насквозь
            }
            if (!wasHittable(target, p.rewindTicks)) {
                continue; // стрелок видел цель в рывке, мёртвой или в прошлой жизни
            }
            if (segmentDistSq(x0, y0, x1, y1, pastPos[0], pastPos[1]) <= hitDist * hitDist) {
                return target;
            }
        }
        return null;
    }

    /** Где была цель {@code rewind} тиков назад → pastPos; false — тогда в неё попасть было нельзя. */
    private boolean wasHittable(Brawler target, float rewind) {
        pastPos[0] = target.x;
        pastPos[1] = target.y;
        return rewind <= 0f || target.history.whereWas(tick - rewind, target.life, pastPos);
    }

    /** Квадрат расстояния от точки (px, py) до отрезка (ax, ay)–(bx, by). */
    static float segmentDistSq(float ax, float ay, float bx, float by, float px, float py) {
        float abx = bx - ax;
        float aby = by - ay;
        float len2 = abx * abx + aby * aby;
        float t = len2 > 0f ? clamp(((px - ax) * abx + (py - ay) * aby) / len2, 0f, 1f) : 0f;
        float dx = ax + abx * t - px;
        float dy = ay + aby * t - py;
        return dx * dx + dy * dy;
    }

    private static boolean insideArena(float x, float y) {
        return x >= 0 && y >= 0 && x <= ARENA_WIDTH && y <= ARENA_HEIGHT;
    }

    // ------------------------------------------------------------------ урон и возрождение

    /** Супер копится только от урона обычными выстрелами. */
    private void chargeSuper(int ownerId, int dealt) {
        Brawler owner = brawlers.get(ownerId);
        if (owner != null) {
            owner.superCharge = Math.min(SUPER_CHARGE_DAMAGE, owner.superCharge + dealt);
        }
    }

    /** Возвращает реально нанесённый урон (не больше оставшегося здоровья). */
    private int damage(Brawler target, int attackerId, int amount) {
        int dealt = Math.min(amount, target.hp);
        target.hp -= dealt;
        if (target.hp > 0) {
            return dealt;
        }
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
        b.life++;
        b.moveX = 0;
        b.moveY = 0;
        b.dashTicksLeft = 0;
    }

    // ------------------------------------------------------------------ снапшот

    /**
     * Состояние бойца для его владельца: заряд супера, кулдаун и рывок (для предсказания), а также
     * seq команд, чьи выстрелы сервер действительно сделал, — клиент убирает предсказанные
     * снаряды, которых на сервере не было.
     */
    SnapshotPacket.OwnState ownState(Brawler b) {
        int charge = Math.round(255f * b.superCharge / SUPER_CHARGE_DAMAGE);
        return new SnapshotPacket.OwnState(charge, Math.min(0xFFFF, b.dodgeCooldownTicks),
                Math.min(255, b.dashTicksLeft), b.dashTicksLeft > 0 ? b.dashAngle : 0,
                b.firedAttackSeq & 0xFFFF, b.firedSuperSeq & 0xFFFF);
    }

    /**
     * События снарядов новее {@code afterTick} (от старых к новым) — то, что клиент, подтвердивший
     * снапшот afterTick, ещё мог не получить. Списки очищаются и заполняются заново.
     */
    void shotEventsAfter(long afterTick, List<SnapshotPacket.ShotSpawn> spawns, List<SnapshotPacket.ShotHit> hits) {
        spawns.clear();
        hits.clear();
        for (SnapshotPacket.ShotSpawn s : shotSpawns) {
            if (s.tick() > afterTick) {
                spawns.add(s);
            }
        }
        for (SnapshotPacket.ShotHit h : shotHits) {
            if (h.tick() > afterTick) {
                hits.add(h);
            }
        }
    }

    private void forgetOldShotEvents() {
        long oldest = tick - SHOT_EVENT_KEEP_TICKS;
        shotSpawns.removeIf(s -> s.tick() < oldest);
        shotHits.removeIf(h -> h.tick() < oldest);
    }

    /** Бойцы для снапшота. Возвращает переиспользуемый список — не сохраняйте его. */
    List<SnapshotPacket.Entity> snapshotEntities() {
        snapshotBuffer.clear();
        for (Brawler b : brawlers.values()) {
            int hp = b.alive ? Math.max(1, Math.round(255f * b.hp / BRAWLER_MAX_HP)) : 0;
            int flags = 0;
            if (b.isDashing()) {
                flags |= SnapshotPacket.Flags.DODGING;
            }
            if (b.superCharge >= SUPER_CHARGE_DAMAGE) {
                flags |= SnapshotPacket.Flags.SUPER_READY;
            }
            if (snapshotBuffer.size() >= MAX_SNAPSHOT_ENTITIES) {
                break; // в реальной игре — область видимости
            }
            snapshotBuffer.add(new SnapshotPacket.Entity(b.id, SnapshotPacket.EntityKind.BRAWLER,
                    Quantize.posToI16(b.x), Quantize.posToI16(b.y), Quantize.angleToU16(b.aim), hp, b.team, flags));
        }
        return snapshotBuffer;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
