package com.ugame.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C_SNAPSHOT — полное состояние мира на тике, ненадёжный канал.
 *
 * <p>Потерянный снапшот не повторяется: следующий всё равно его заменит. Клиент интерполирует
 * чужие сущности между двумя последними снапшотами, а свою — сверяет с ackInputSeq
 * (client-side prediction + reconciliation).
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
 * u8  entityCount
 * entityCount × {
 *   u16 id
 *   u8  kind          EntityKind
 *   i16 x             сантиметры
 *   i16 y
 *   u16 aim           угол, 0..65535
 *   u8  hp            0..255 (доля от максимума)
 *   u8  team
 *   u8  flags         битовая маска Flags
 * }
 * </pre>
 *
 * Размер: 18 + 12 × N байт. Лимит ненадёжного сообщения kcp2k при MTU 1200 — 1194 байта,
 * т. е. не больше 98 сущностей. Если мир больше — дельта-сжатие или область видимости.
 */
public final class SnapshotPacket {
    private SnapshotPacket() {}

    public static final int HEADER_SIZE = 18;
    public static final int ENTITY_SIZE = 12;

    public static final class EntityKind {
        private EntityKind() {}

        public static final int BRAWLER = 1;
        public static final int PROJECTILE = 2;
    }

    /** Флаги сущности (видны всем игрокам). */
    public static final class Flags {
        private Flags() {}

        public static final int DODGING = 1;          // боец в рывке и неуязвим
        public static final int SUPER_READY = 1 << 1; // у бойца заряжен супер
        public static final int SUPER_SHOT = 1 << 2;  // снаряд супер удара
    }

    /** Состояние своего бойца — уходит только его владельцу. */
    public record OwnState(int superCharge, int dodgeCooldownTicks, int dashTicksLeft, int dashAngle) {
        public static final OwnState NONE = new OwnState(0, 0, 0, 0);
    }

    public record Header(long tick, int ackInputSeq, int timeLeftSec, int scoreTeam0, int scoreTeam1, OwnState own) {}

    public record Entity(int id, int kind, int x, int y, int aim, int hp, int team, int flags) {}

    public record Snapshot(Header header, List<Entity> entities) {}

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
                .u8(entityCount);
    }

    /** Читает пакет без байта типа. Нужен для тестов и ботов; клиент делает то же самое на C#. */
    public static Snapshot readBody(ByteReader r) {
        Header h = new Header(r.u32(), r.u16(), r.u16(), r.u8(), r.u8(),
                new OwnState(r.u8(), r.u16(), r.u8(), r.u16()));
        int count = r.u8();
        List<Entity> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new Entity(r.u16(), r.u8(), r.i16(), r.i16(), r.u16(), r.u8(), r.u8(), r.u8()));
        }
        return new Snapshot(h, list);
    }
}
