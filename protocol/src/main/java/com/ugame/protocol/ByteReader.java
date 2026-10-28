package com.ugame.protocol;

/**
 * Чтение little-endian значений из массива. Все методы проверяют границы и бросают
 * {@link MalformedPacketException}: данные приходят из сети и могут быть мусором или атакой.
 */
public final class ByteReader {
    private byte[] buf;
    private int pos;
    private int end;

    public ByteReader() {}

    public ByteReader(byte[] buf) {
        wrap(buf, 0, buf.length);
    }

    public ByteReader wrap(byte[] buf, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > buf.length) {
            throw new IllegalArgumentException("bad range");
        }
        this.buf = buf;
        this.pos = offset;
        this.end = offset + length;
        return this;
    }

    public int remaining() {
        return end - pos;
    }

    private void need(int n) {
        if (end - pos < n) {
            throw new MalformedPacketException("packet too short: need " + n + ", have " + (end - pos));
        }
    }

    public int u8() {
        need(1);
        return buf[pos++] & 0xFF;
    }

    public int i8() {
        need(1);
        return buf[pos++];
    }

    public int u16() {
        need(2);
        int v = (buf[pos] & 0xFF) | ((buf[pos + 1] & 0xFF) << 8);
        pos += 2;
        return v;
    }

    public int i16() {
        return (short) u16();
    }

    public int i32() {
        need(4);
        int v = (buf[pos] & 0xFF)
                | ((buf[pos + 1] & 0xFF) << 8)
                | ((buf[pos + 2] & 0xFF) << 16)
                | ((buf[pos + 3] & 0xFF) << 24);
        pos += 4;
        return v;
    }

    public long u32() {
        return i32() & 0xFFFFFFFFL;
    }

    public static final class MalformedPacketException extends RuntimeException {
        public MalformedPacketException(String message) {
            super(message, null, false, false); // без стектрейса: дёшево при мусорных пакетах
        }
    }
}
