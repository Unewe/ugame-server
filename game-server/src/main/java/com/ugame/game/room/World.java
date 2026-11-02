package com.ugame.game.room;

import com.ugame.protocol.InputPacket;
import com.ugame.protocol.Quantize;
import com.ugame.protocol.SnapshotPacket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * <p><b>Компенсация лагов.</b> Стрелок целится во врагов, которых видит в прошлом: снапшот идёт
 * к нему RTT/2, клиент показывает чужих с задержкой интерполяции, и ещё RTT/2 летит его ввод.
 * Свои выстрелы клиент рисует сразу (предсказание), поэтому на его экране «снаряд в полёте k тиков»
 * встречается с врагами, отстающими на RTT + интерполяцию. Сервер повторяет ровно это: у каждого
 * бойца хранится история позиций, а попадание снаряда проверяется по позиции цели, отмотанной на
 * {@link #rewindTicks} стрелка. Отмотка фиксируется в момент выстрела и не больше maxRewind —
 * при большом пинге стрелку придётся брать упреждение, зато цель не получает урон «из-за угла».
 * Рывок честен для уклоняющегося: если цель в рывке сейчас или была в рывке в отмотанный момент,
 * снаряд проходит насквозь. Цель, которая успела умереть и возродиться, по старым позициям не бьётся.
 *
 * <p><b>Ввод.</b> Команды клиента (одна на его тик) встают в очередь бойца и выполняются по одной
 * за тик сервера — тем же шагом, что предсказание в MatchView. Сервер не «додумывает» движение по
 * последнему вводу и не склеивает несколько команд в один шаг, поэтому позиция после команды N
 * у сервера и у клиента совпадает, и своему бойцу не нужны поправки. Джиттер сети гасит маленький
 * запас в очереди (см. {@link #runQueuedInput}).
 *
 * <p>Кулдаун обычной атаки считается по номерам команд (seq), а не по тикам сервера: так клиент
 * точно знает, какой выстрел сервер примет, и может показать его сразу. Защита от «ускоренного»
 * клиента — минимальный интервал в тиках сервера с запасом на джиттер (ATTACK_JITTER_SLACK).
 *
 * <p>Логику движения, рывка и стрельбы повторяет предсказание в MatchView.cs — меняете здесь,
 * меняйте и там. Единицы: метры и секунды; время внутри — в тиках.
 */
final class World {
    static final float ARENA_WIDTH = 20f;
    static final float ARENA_HEIGHT = 30f;

    static final float BRAWLER_RADIUS = 0.5f;
    static final float BRAWLER_SPEED = 5f;      // м/с
    static final int BRAWLER_MAX_HP = 3000;
    static final float ATTACK_COOLDOWN_SEC = 0.5f;
    /** Доля кулдауна, на которую тики сервера могут «сжаться» из-за джиттера сети. */
    static final float ATTACK_JITTER_SLACK = 1f / 3f;
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

    // --- компенсация лагов (значения по умолчанию; в бою — из RoomSettings) ---
    /** Задержка показа чужих сущностей на клиенте, тиков (MatchView.InterpDelayTicks). */
    static final int DEFAULT_INTERP_DELAY_TICKS = 3;
    /** Дальше в прошлое не отматываем. 0 — компенсация выключена. */
    static final int DEFAULT_MAX_REWIND_MS = 300;

    // --- id сущностей ---
    // Бойцы получают id 1..255: id владельца снаряда передаётся в снапшоте одним байтом.
    static final int MAX_BRAWLER_ID = 255;
    static final int FIRST_PROJECTILE_ID = 256;
    static final int MAX_PROJECTILE_ID = 0xFFFF;  // дальше id снарядов идут по кругу

    // --- очередь ввода ---
    /** Сколько команд держим «в запасе» от джиттера; больше — догоняем по две за тик. */
    static final int INPUT_QUEUE_TARGET = 2;
    /** Запас кредита: столько команд сверх «одна за тик» можно выполнить, догоняя задержку. */
    static final int INPUT_CREDIT_MAX = 4;
    /** Больше команд в очереди не держим (≈ 270 мс при 30 Гц). */
    static final int MAX_INPUT_QUEUE = 8;

    /** Лимит сущностей в одном ненадёжном снапшоте (MTU 1200). */
    static final int MAX_SNAPSHOT_ENTITIES = 95; // (1194 − 22) / 12 = 97

    private final int tickRate;
    private final float dt;
    private final int dashTicks;
    private final int dodgeCooldownTicks;
    private final float dashStep;   // м за тик рывка
    private final int attackCooldownTicks;
    private final int attackMinTicks;      // минимум тиков сервера между выстрелами (анти-спидхак)
    private final float interpDelayTicks;
    private final float maxRewindTicks;
    private final int historySize;
    private final Map<Integer, Brawler> brawlers = new LinkedHashMap<>();
    private final List<Projectile> projectiles = new ArrayList<>();
    private final int[] teamScore = new int[2];
    private final List<SnapshotPacket.Entity> snapshotBuffer = new ArrayList<>();
    private int nextBrawlerId = 1;
    private int nextProjectileId = FIRST_PROJECTILE_ID;
    private long tick;

    // результат pastPosition — без выделения памяти на каждую проверку
    private float pastX;
    private float pastY;

    World(int tickRate) {
        this(tickRate, DEFAULT_INTERP_DELAY_TICKS, DEFAULT_MAX_REWIND_MS);
    }

    /**
     * @param interpDelayTicks на сколько тиков клиент показывает чужих в прошлом (MatchView.InterpDelayTicks)
     * @param maxRewindMs      предел отмотки при проверке попаданий; 0 — без компенсации лагов
     */
    World(int tickRate, int interpDelayTicks, int maxRewindMs) {
        this.tickRate = tickRate;
        this.dt = 1f / tickRate;
        this.dashTicks = Math.max(1, Math.round(DASH_DURATION_SEC * tickRate));
        this.dodgeCooldownTicks = Math.round(DODGE_COOLDOWN_SEC * tickRate);
        this.dashStep = DASH_DISTANCE / dashTicks;
        this.attackCooldownTicks = Math.max(1, Math.round(ATTACK_COOLDOWN_SEC * tickRate));
        this.attackMinTicks = Math.max(1, Math.round(attackCooldownTicks * (1f - ATTACK_JITTER_SLACK)));
        this.interpDelayTicks = Math.max(0, interpDelayTicks);
        this.maxRewindTicks = Math.max(0f, maxRewindMs * tickRate / 1000f);
        this.historySize = (int) Math.ceil(maxRewindTicks) + 2;
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
        if (nextBrawlerId > MAX_BRAWLER_ID) {
            throw new IllegalStateException("too many brawlers in one match");
        }
        Brawler b = new Brawler(nextBrawlerId++, playerId, team & 1, historySize);
        respawn(b);
        brawlers.put(b.id, b);
        return b;
    }

    // ------------------------------------------------------------------ компенсация лагов

    /**
     * Сглаженный RTT игрока, мс (KCP ping/pong раз в секунду). -1 — ещё не измерен или игрок
     * отключился: тогда его выстрелы проверяются без отмотки.
     */
    void setRtt(Brawler b, int rttMs) {
        b.rttMs = rttMs;
    }

    /**
     * На сколько тиков назад смотреть, проверяя попадания этого стрелка: RTT (снапшот к нему
     * + его ввод к нам) плюс задержка интерполяции на клиенте, но не больше maxRewind.
     */
    float rewindTicks(Brawler shooter) {
        if (maxRewindTicks <= 0f || shooter.rttMs < 0) {
            return 0f;
        }
        return Math.min(maxRewindTicks, shooter.rttMs * tickRate / 1000f + interpDelayTicks);
    }

    /**
     * Позиция цели {@code rewind} тиков назад (с интерполяцией между тиками) → pastX/pastY.
     * false — в тот момент по цели нельзя было попасть: была в рывке, мертва или это была её
     * прошлая жизнь (с тех пор умерла и возродилась).
     */
    private boolean pastPosition(Brawler t, float rewind) {
        pastX = t.x;
        pastY = t.y;
        if (rewind <= 0f) {
            return true;
        }
        double at = tick - rewind;
        long t0 = (long) Math.floor(at);
        float f = (float) (at - t0);
        int i0 = t.historyIndex(t0);
        int i1 = t.historyIndex(t0 + 1);
        if (i0 < 0 && i1 < 0) {
            return true; // истории ещё нет (боец только что появился) — по текущей позиции
        }
        if (i0 < 0) { // боец появился между t0 и t0 + 1
            i0 = i1;
            f = 0f;
        } else if (i1 < 0) {
            i1 = i0;
            f = 0f;
        }
        if (!t.hittableAt(i0) || !t.hittableAt(i1)) {
            return false;
        }
        pastX = t.histX[i0] + (t.histX[i1] - t.histX[i0]) * f;
        pastY = t.histY[i0] + (t.histY[i1] - t.histY[i0]) * f;
        return true;
    }

    // ------------------------------------------------------------------ ввод

    /**
     * Ставит команду в очередь бойца (поток комнаты). Дубли и устаревшие (пакет содержит несколько
     * последних команд, UDP может переставить пакеты) отбрасываются.
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
     * Игрок вошёл заново (новое подключение — нумерация команд с начала). Боец остаётся тем же,
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

    /**
     * Сколько команд выполнить в этом тике. Обычно одну. Если очередь выросла (после джиттера пришла
     * пачка) — две, пока не вернёмся к INPUT_QUEUE_TARGET: одна команда «в запасе» сглаживает
     * следующий провал, вторая — догоняем задержку. Кредит (+1 за тик, не больше INPUT_CREDIT_MAX)
     * не даёт клиенту, шлющему команды чаще tickRate, бегать быстрее остальных.
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
     * Один шаг бойца по одной команде — ровно то, что делает MatchView.Step на клиенте
     * (кулдаун рывка, начало рывка, движение), плюс выстрелы. Поэтому состояние сервера после
     * команды N совпадает с предсказанием клиента после той же команды бит в бит, и при сверке
     * по ackInputSeq поправок нет, пока не вмешается кто-то другой (урон, смерть).
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

        // Сколько команда ждала в очереди сверх обычного тика — на столько дальше в прошлом
        // её видел стрелок; добавляем к отмотке.
        float queued = Math.max(0, tick - q.receivedTick() - 1);
        if (cmd.pressed(InputPacket.Buttons.ATTACK) && attackReady(b, cmd.seq())) {
            b.lastAttackCmdSeq = cmd.seq();
            b.lastAttackTick = tick;
            spawnProjectile(b, b.aim, false, queued);
            b.firedAttackSeq = cmd.seq();
        }
        if (cmd.pressed(InputPacket.Buttons.SUPER) && b.superCharge >= SUPER_CHARGE_DAMAGE) {
            fireSuper(b, queued);
            b.superCharge = 0;
            b.firedSuperSeq = cmd.seq();
        }
    }

    /**
     * Кулдаун атаки — в командах клиента (одна команда = один тик клиента), так его повторяет
     * предсказание в MatchView. Плюс нижняя граница в тиках сервера: команды могут прийти пачкой,
     * но стрелять чаще, чем раз в attackMinTicks, нельзя.
     */
    private boolean attackReady(Brawler b, int seq) {
        boolean bySeq = b.lastAttackCmdSeq < 0 || ((seq - b.lastAttackCmdSeq) & 0xFFFF) >= attackCooldownTicks;
        return bySeq && tick - b.lastAttackTick >= attackMinTicks;
    }

    // ------------------------------------------------------------------ тик

    void step() {
        tick++;
        for (Brawler b : brawlers.values()) {
            if (!b.alive) {
                if (b.dodgeCooldownTicks > 0) {
                    b.dodgeCooldownTicks--; // пока боец мёртв, кулдаун идёт по тикам
                }
                // команды, отправленные до того, как клиент узнал о смерти, только подтверждаем
                QueuedCommand q;
                while ((q = b.inputQueue.pollFirst()) != null) {
                    b.lastInputSeq = q.cmd().seq();
                }
                if (tick >= b.respawnTick) {
                    respawn(b);
                }
                continue;
            }
            // Живой боец двигается только по командам. Нет команд (лаг, игрок отключился) —
            // стоит на месте, а не идёт дальше по последнему вводу, которого клиент не предсказывал.
            runQueuedInput(b);
        }
        for (Brawler b : brawlers.values()) {
            b.record(tick); // состояние после движения — то же, что уйдёт в снапшот этого тика
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

    /** Веер из SUPER_SHOTS снарядов, симметрично относительно прицела. */
    private void fireSuper(Brawler b, float extraRewind) {
        for (int i = 0; i < SUPER_SHOTS; i++) {
            float t = SUPER_SHOTS == 1 ? 0f : (float) i / (SUPER_SHOTS - 1) - 0.5f; // -0.5..0.5
            spawnProjectile(b, b.aim + t * SUPER_SPREAD_RAD, true, extraRewind);
        }
    }

    private void spawnProjectile(Brawler b, float angle, boolean isSuper, float extraRewind) {
        float speed = isSuper ? SUPER_SPEED : PROJECTILE_SPEED;
        float radius = isSuper ? SUPER_RADIUS : PROJECTILE_RADIUS;
        float dx = (float) Math.cos(angle);
        float dy = (float) Math.sin(angle);
        float rewind = rewindTicks(b) > 0f ? Math.min(maxRewindTicks, rewindTicks(b) + extraRewind) : 0f;
        Projectile p = new Projectile(nextProjectileId(), b.id, b.team, isSuper, rewind);
        p.x = b.x + dx * (BRAWLER_RADIUS + radius);
        p.y = b.y + dy * (BRAWLER_RADIUS + radius);
        p.vx = dx * speed;
        p.vy = dy * speed;
        p.aim = angle;
        projectiles.add(p);
    }

    private int nextProjectileId() {
        int id = nextProjectileId;
        nextProjectileId = id >= MAX_PROJECTILE_ID ? FIRST_PROJECTILE_ID : id + 1;
        return id;
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
                    continue; // мёртвых не бьём; в рывке снаряды пролетают насквозь
                }
                if (!pastPosition(target, p.rewindTicks)) {
                    continue; // стрелок видел цель в рывке, мёртвой или в прошлой жизни
                }
                float dx = pastX - p.x;
                float dy = pastY - p.y;
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
        b.life++;
        b.moveX = 0;
        b.moveY = 0;
        b.dashTicksLeft = 0;
    }

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
            // у снаряда поле hp — id бойца-владельца: свои снаряды клиент рисует сам
            snapshotBuffer.add(new SnapshotPacket.Entity(p.id, SnapshotPacket.EntityKind.PROJECTILE,
                    Quantize.posToI16(p.x), Quantize.posToI16(p.y), Quantize.angleToU16(p.aim), p.ownerId, p.team,
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
        int life;                 // номер жизни: растёт при каждом возрождении
        final ArrayDeque<QueuedCommand> inputQueue = new ArrayDeque<>();
        int lastQueuedSeq = -1;   // последняя принятая в очередь команда
        int inputCredit = INPUT_CREDIT_MAX;
        int firedAttackSeq;       // seq последней атаки, которая реально вылетела (для клиента)
        int firedSuperSeq;        // то же для супера
        int lastAttackCmdSeq = -1;
        int superCharge;          // накопленный урон, 0..SUPER_CHARGE_DAMAGE
        int dodgeCooldownTicks;   // 0 — уклонение готово
        int dashTicksLeft;        // > 0 — боец в рывке и неуязвим
        int dashAngle;            // u16
        float dashDx;
        float dashDy;
        int rttMs = -1;           // -1 — не измерен (или игрок отключён)
        long lastAttackTick = Long.MIN_VALUE / 2;
        long respawnTick;
        int lastInputSeq = -1; // последняя ВЫПОЛНЕННАЯ команда (ack для клиента); -1 — ещё не было
        int kills;
        int deaths;

        // история для компенсации лагов: кольцевой буфер по номеру тика
        private final long[] histTick;
        final float[] histX;
        final float[] histY;
        private final int[] histLife;     // 0 — мёртв
        private final boolean[] histDashing;

        Brawler(int id, long playerId, int team, int historySize) {
            this.id = id;
            this.playerId = playerId;
            this.team = team;
            this.histTick = new long[historySize];
            this.histX = new float[historySize];
            this.histY = new float[historySize];
            this.histLife = new int[historySize];
            this.histDashing = new boolean[historySize];
            Arrays.fill(histTick, -1);
        }

        void record(long tick) {
            int i = (int) Math.floorMod(tick, (long) histTick.length);
            histTick[i] = tick;
            histX[i] = x;
            histY[i] = y;
            histLife[i] = alive ? life : 0;
            histDashing[i] = alive && dashTicksLeft > 0;
        }

        /** Индекс записи за этот тик или -1, если её нет (слишком давно или боец появился позже). */
        int historyIndex(long tick) {
            int i = (int) Math.floorMod(tick, (long) histTick.length);
            return histTick[i] == tick ? i : -1;
        }

        /** Можно ли было попасть в бойца в этот момент истории (жив, та же жизнь, не в рывке). */
        boolean hittableAt(int i) {
            return histLife[i] == life && !histDashing[i];
        }

    }

    /** Команда в очереди и тик, когда она пришла (для учёта ожидания в отмотке). */
    record QueuedCommand(InputPacket.Command cmd, long receivedTick) {}

    static final class Projectile {
        final int id;
        final int ownerId;
        final int team;
        final boolean isSuper;
        final float rewindTicks;  // отмотка стрелка на момент выстрела
        float x;
        float y;
        float vx;
        float vy;
        float aim;
        float traveled;

        Projectile(int id, int ownerId, int team, boolean isSuper, float rewindTicks) {
            this.id = id;
            this.ownerId = ownerId;
            this.team = team;
            this.isSuper = isSuper;
            this.rewindTicks = rewindTicks;
        }
    }
}
