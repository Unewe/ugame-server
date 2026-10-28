package com.ugame.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * C2S_INPUT — ввод игрока, ненадёжный канал.
 *
 * <p>Чтобы потеря одного UDP-пакета не теряла ввод, клиент кладёт в пакет несколько последних
 * команд (от новой к старой). Сервер применяет только те, у которых seq новее уже обработанного.
 *
 * <pre>
 * u8  type = 20
 * u8  count (1..MAX_COMMANDS)
 * count × {
 *   u16 seq        номер команды (растёт на 1 каждый тик клиента, с переполнением)
 *   i8  moveX      ось движения  [-127..127]
 *   i8  moveY
 *   u16 aim        угол прицела, 0..65535 = 0..2π
 *   u8  buttons    битовая маска Buttons
 * }
 * </pre>
 */
public final class InputPacket {
    private InputPacket() {}

    public static final int MAX_COMMANDS = 4;
    public static final int COMMAND_SIZE = 7;

    public static final class Buttons {
        private Buttons() {}

        public static final int ATTACK = 1;
        public static final int SUPER = 1 << 1;
    }

    public record Command(int seq, int moveX, int moveY, int aim, int buttons) {
        public boolean pressed(int button) {
            return (buttons & button) != 0;
        }
    }

    /** commands — от самой новой к старой; лишние отбрасываются. */
    public static void write(ByteWriter w, List<Command> commands) {
        int count = Math.min(commands.size(), MAX_COMMANDS);
        if (count == 0) {
            throw new IllegalArgumentException("no commands");
        }
        w.u8(MessageType.C2S_INPUT).u8(count);
        for (int i = 0; i < count; i++) {
            Command c = commands.get(i);
            w.u16(c.seq()).i8(c.moveX()).i8(c.moveY()).u16(c.aim()).u8(c.buttons());
        }
    }

    /** Читает пакет без байта типа (его уже прочитал диспетчер). */
    public static List<Command> readBody(ByteReader r) {
        int count = r.u8();
        if (count < 1 || count > MAX_COMMANDS) {
            throw new ByteReader.MalformedPacketException("bad command count " + count);
        }
        List<Command> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new Command(r.u16(), r.i8(), r.i8(), r.u16(), r.u8()));
        }
        return list;
    }
}
