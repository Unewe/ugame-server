package com.ugame.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C_SNAPSHOT — состояние мира на тике, ненадёжный канал.
 *
 * <p>Потерянный снапшот не повторяется: следующий всё равно его заменит. Клиент интерполирует
 * чужих бойцов между двумя снапшотами, а своего — сверяет с ackInputSeq (prediction + reconciliation).
 *
 * <p>Снаряды летят по прямой с постоянной скоростью, поэтому их не передают каждый тик: приходят
 * события «выстрел» (точка, угол, тик) и «попадание» (тик), а полёт клиент считает сам. Чтобы
 * потеря пакета не теряла событие, сервер повторяет его в каждом снапшоте, пока клиент не
 * подтвердит снапшот с этим событием (ackSnapshotTick во вводе, см. {@link InputPacket}).
 *
 * <pre>
 * u8  type = 21
 * u32 tick
 * u16 ackInputSeq     последняя обработанная команда ЭТОГО клиента
 * u16 timeLeftSec
 * u8  scoreTeam0
 * u8  scoreTeam1
 * u8  superCharge     заряд супера ЭТОГО клиента, 0..255 (255 — готов)
 * u16 dodgeCooldown   тиков до готовности уклонения у ЭТОГО клиента
 * u8  dashTicksLeft   сколько тиков рывка осталось у ЭТОГО клиента (для предсказания)
 * u16 dashAngle       направление текущего рывка, 0..65535
 * u16 lastAttackSeq   seq команды последней атаки ЭТОГО клиента, которую сервер выпустил
 * u16 lastSuperSeq    то же для супера (клиент убирает предсказанные выстрелы, которых не было)
 * u8  entityCount
 * entityCount × {     бойцы
 *   u16 id
 *   u8  kind          EntityKind
 *   i16 x             сантиметры
 *   i16 y
 *   u16 aim           угол, 0..65535
 *   u8  hp            0..255 (доля от максимума)
 *   u8  team
 *   u8  flags         битовая маска Flags
 * }
 * u8  spawnCount
 * spawnCount × {      выстрелы
 *   u16 id            id снаряда
 *   u8  age           tick − тик выстрела
 *   i16 x             точка вылета, сантиметры
 *   i16 y
 *   u16 angle         направление, 0..65535
 *   u8  owner         id бойца-стрелка
 *   u8  team
 *   u8  kind          ShotKind
 * }
 * u8  hitCount
 * hitCount × {        попадания: снаряд исчез в этом тике (долёт до конца дальности клиент считает сам)
 *   u16 id
 *   u8  age           tick − тик попадания
 * }
 * </pre>
 *
 * Размер: 24 + 12 × бойцов + 12 × выстрелов + 3 × попаданий; всё — в одном UDP-пакете
 * ({@link #MAX_SIZE} байт при MTU 1200).
 */
public final class SnapshotPacket {
    private SnapshotPacket() {}

    /** Лимит ненадёжного сообщения kcp2k при MTU 1200. */
    public static final int MAX_SIZE = 1194;
    public static final int HEADER_SIZE = 22;
    public static final int ENTITY_SIZE = 12;
    public static final int SPAWN_SIZE = 12;
    public static final int HIT_SIZE = 3;
    /** Счётчики выстрелов и попаданий. */
    public static final int EVENT_COUNTS_SIZE = 2;
    /** Событие старше этого (в тиках) в пакет не помещается — поле age однобайтовое. */
    public static final int MAX_EVENT_AGE = 255;

    public static final class EntityKind {
        private EntityKind() {}

        public static final int BRAWLER = 1;
    }

    /** Флаги сущности (видны всем игрокам). */
    public static final class Flags {
        private Flags() {}

        public static final int DODGING = 1;          // боец в рывке и неуязвим
        public static final int SUPER_READY = 1 << 1; // у бойца заряжен супер
    }

    /** Тип снаряда в событии «выстрел». */
    public static final class ShotKind {
        private ShotKind() {}

        public static final int NORMAL = 0;
        public static final int SUPER = 1;
    }

    /** Состояние своего бойца — уходит только его владельцу. */
    public record OwnState(int superCharge, int dodgeCooldownTicks, int dashTicksLeft, int dashAngle,
                           int lastAttackSeq, int lastSuperSeq) {}

    public record Header(long tick, int ackInputSeq, int timeLeftSec, int scoreTeam0, int scoreTeam1, OwnState own) {}

    public record Entity(int id, int kind, int x, int y, int aim, int hp, int team, int flags) {}

    /** Выстрел в тике {@code tick} из точки (x, y) (сантиметры) в направлении angle (0..65535). */
    public record ShotSpawn(int id, long tick, int x, int y, int angle, int owner, int team, int kind) {}

    /** Снаряд {@code id} попал в кого-то и исчез в тике {@code tick}. */
    public record ShotHit(int id, long tick) {}

    public record Snapshot(Header header, List<Entity> entities, List<ShotSpawn> spawns, List<ShotHit> hits) {}

    /** Тело сущностей пишется один раз на тик и копируется в пакет каждого клиента. */
    public static void writeEntities(ByteWriter w, List<Entity> entities) {
        for (Entity e : entities) {
            w.u16(e.id()).u8(e.kind()).i16(e.x()).i16(e.y()).u16(e.aim()).u8(e.hp()).u8(e.team()).u8(e.flags());
        }
    }

    public static void writeHeader(ByteWriter w, Header h, int entityCount) {
        if (entityCount > 255) {
            throw new IllegalArgumentException("too many entities: " + entityCount);
        }
        w.u8(MessageType.S2C_SNAPSHOT)
                .u32(h.tick())
                .u16(h.ackInputSeq())
                .u16(h.timeLeftSec())
                .u8(h.scoreTeam0())
                .u8(h.scoreTeam1())
                .u8(h.own().superCharge())
                .u16(h.own().dodgeCooldownTicks())
                .u8(h.own().dashTicksLeft())
                .u16(h.own().dashAngle())
                .u16(h.own().lastAttackSeq())
                .u16(h.own().lastSuperSeq())
                .u8(entityCount);
    }

    /**
     * События снарядов — после сущностей. Пишет столько, сколько влезает в {@link #MAX_SIZE}:
     * сначала самые новые выстрелы (старые, скорее всего, клиент уже получил), потом попадания.
     * Не влезшее уйдёт следующим снапшотом — события повторяются до подтверждения.
     *
     * @param spawns от старых к новым; слишком старые (age > MAX_EVENT_AGE) пропускаются
     * @param hits   от старых к новым
     */
    public static void writeShotEvents(ByteWriter w, long tick, List<ShotSpawn> spawns, List<ShotHit> hits) {
        int room = MAX_SIZE - w.size() - EVENT_COUNTS_SIZE;
        int spawnCount = fitting(spawns.size(), room / SPAWN_SIZE);
        room -= spawnCount * SPAWN_SIZE;
        int hitCount = fitting(hits.size(), room / HIT_SIZE);

        w.u8(spawnCount);
        for (int i = spawns.size() - spawnCount; i < spawns.size(); i++) {
            ShotSpawn s = spawns.get(i);
            w.u16(s.id()).u8(age(tick, s.tick())).i16(s.x()).i16(s.y()).u16(s.angle())
                    .u8(s.owner()).u8(s.team()).u8(s.kind());
        }
        w.u8(hitCount);
        for (int i = hits.size() - hitCount; i < hits.size(); i++) {
            ShotHit h = hits.get(i);
            w.u16(h.id()).u8(age(tick, h.tick()));
        }
    }

    private static int fitting(int available, int capacity) {
        return Math.max(0, Math.min(Math.min(available, capacity), 255));
    }

    private static int age(long tick, long eventTick) {
        long age = tick - eventTick;
        if (age < 0 || age > MAX_EVENT_AGE) {
            throw new IllegalArgumentException("event age out of range: " + age);
        }
        return (int) age;
    }

    /** Читает пакет без байта типа. Нужен для тестов и ботов; клиент делает то же самое на C#. */
    public static Snapshot readBody(ByteReader r) {
        Header h = new Header(r.u32(), r.u16(), r.u16(), r.u8(), r.u8(),
                new OwnState(r.u8(), r.u16(), r.u8(), r.u16(), r.u16(), r.u16()));
        int count = r.u8();
        List<Entity> entities = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entities.add(new Entity(r.u16(), r.u8(), r.i16(), r.i16(), r.u16(), r.u8(), r.u8(), r.u8()));
        }
        int spawnCount = r.u8();
        List<ShotSpawn> spawns = new ArrayList<>(spawnCount);
        for (int i = 0; i < spawnCount; i++) {
            int id = r.u16();
            long tick = h.tick() - r.u8();
            spawns.add(new ShotSpawn(id, tick, r.i16(), r.i16(), r.u16(), r.u8(), r.u8(), r.u8()));
        }
        int hitCount = r.u8();
        List<ShotHit> hits = new ArrayList<>(hitCount);
        for (int i = 0; i < hitCount; i++) {
            int id = r.u16();
            hits.add(new ShotHit(id, h.tick() - r.u8()));
        }
        return new Snapshot(h, entities, spawns, hits);
    }
}
