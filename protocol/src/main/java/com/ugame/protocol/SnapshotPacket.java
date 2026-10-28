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
 * u8  entityCount
 * entityCount × {
 *   u16 id
 *   u8  kind          EntityKind
 *   i16 x             сантиметры
 *   i16 y
 *   u16 aim           угол, 0..65535
 *   u8  hp            0..255 (доля от максимума)
 *   u8  team
 * }
 * </pre>
 *
 * Размер: 12 + 11 × N байт. Лимит ненадёжного сообщения kcp2k при MTU 1200 — 1194 байта,
 * т. е. не больше 107 сущностей. Если мир больше — дельта-сжатие или область видимости.
 */
public final class SnapshotPacket {
    private SnapshotPacket() {}

    public static final int HEADER_SIZE = 12;
    public static final int ENTITY_SIZE = 11;

    public static final class EntityKind {
        private EntityKind() {}

        public static final int BRAWLER = 1;
        public static final int PROJECTILE = 2;
    }

    public record Header(long tick, int ackInputSeq, int timeLeftSec, int scoreTeam0, int scoreTeam1) {}

    public record Entity(int id, int kind, int x, int y, int aim, int hp, int team) {}

    public record Snapshot(Header header, List<Entity> entities) {}

    /** Тело сущностей пишется один раз на тик и копируется в пакет каждого клиента. */
    public static void writeEntities(ByteWriter w, List<Entity> entities) {
        for (Entity e : entities) {
            w.u16(e.id()).u8(e.kind()).i16(e.x()).i16(e.y()).u16(e.aim()).u8(e.hp()).u8(e.team());
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
                .u8(entityCount);
    }

    /** Читает пакет без байта типа. Нужен для тестов и ботов; клиент делает то же самое на C#. */
    public static Snapshot readBody(ByteReader r) {
        Header h = new Header(r.u32(), r.u16(), r.u16(), r.u8(), r.u8());
        int count = r.u8();
        List<Entity> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new Entity(r.u16(), r.u8(), r.i16(), r.i16(), r.u16(), r.u8(), r.u8()));
        }
        return new Snapshot(h, list);
    }
}
