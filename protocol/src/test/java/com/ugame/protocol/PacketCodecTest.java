package com.ugame.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PacketCodecTest {

    @Test
    void inputRoundTrip() {
        var cmds = List.of(
                new InputPacket.Command(65535, 127, -127, 40000, InputPacket.Buttons.ATTACK),
                new InputPacket.Command(65534, 0, 5, 1, 0));
        ByteWriter w = new ByteWriter();
        InputPacket.write(w, cmds);
        assertEquals(2 + 2 * InputPacket.COMMAND_SIZE, w.size());

        ByteReader r = new ByteReader(w.toArray());
        assertEquals(MessageType.C2S_INPUT, r.u8());
        assertEquals(cmds, InputPacket.readBody(r));
        assertEquals(0, r.remaining());
    }

    @Test
    void snapshotRoundTrip() {
        var header = new SnapshotPacket.Header(4_000_000_000L, 123, 90, 3, 7);
        var entities = List.of(
                new SnapshotPacket.Entity(1, SnapshotPacket.EntityKind.BRAWLER, -1500, 2999, 65535, 255, 1),
                new SnapshotPacket.Entity(2, SnapshotPacket.EntityKind.PROJECTILE, 32767, -32768, 0, 0, 0));
        ByteWriter w = new ByteWriter();
        SnapshotPacket.writeHeader(w, header, entities.size());
        SnapshotPacket.writeEntities(w, entities);
        assertEquals(SnapshotPacket.HEADER_SIZE + 2 * SnapshotPacket.ENTITY_SIZE, w.size());

        ByteReader r = new ByteReader(w.toArray());
        assertEquals(MessageType.S2C_SNAPSHOT, r.u8());
        var s = SnapshotPacket.readBody(r);
        assertEquals(header, s.header());
        assertEquals(entities, s.entities());
    }

    @Test
    void truncatedPacketIsRejected() {
        ByteReader r = new ByteReader(new byte[] {2, 0, 0});
        assertThrows(ByteReader.MalformedPacketException.class, () -> InputPacket.readBody(r));
    }

    @Test
    void sequenceWrapAround() {
        assertTrue(Quantize.seqGreater(0, 65535));
        assertTrue(Quantize.seqGreater(10, 5));
        assertFalse(Quantize.seqGreater(5, 10));
        assertFalse(Quantize.seqGreater(7, 7));
    }

    @Test
    void quantization() {
        assertEquals(1234, Quantize.posToI16(12.34f));
        assertEquals(Short.MAX_VALUE, Quantize.posToI16(10_000f));
        assertEquals(-127, Quantize.axisToI8(-2f));
        assertEquals(0, Quantize.angleToU16((float) (Math.PI * 2)));
        assertEquals(Math.PI, Quantize.u16ToAngle(Quantize.angleToU16((float) Math.PI)), 1e-3);
    }
}
