package com.ugame.protocol;

import java.util.Arrays;

/**
 * Простой растущий буфер для ручной упаковки пакетов. Порядок байтов — little-endian
 * (как BitConverter в C# на x86/ARM и как в kcp2k).
 *
 * <p>Объект переиспользуется: {@link #reset()} перед каждым пакетом, чтобы не плодить мусор.
 */
public final class ByteWriter {
    private byte[] buf;
    private int pos;

    public ByteWriter() {
        this(256);
    }

    public ByteWriter(int initialCapacity) {
        this.buf = new byte[initialCapacity];
    }

    public ByteWriter reset() {
        pos = 0;
        return this;
    }

    public int size() {
        return pos;
    }

    /** Внутренний массив; валидны байты [0, size()). */
    public byte[] array() {
        return buf;
    }

    public byte[] toArray() {
        return Arrays.copyOf(buf, pos);
    }

    private void ensure(int extra) {
        if (pos + extra > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + extra));
        }
    }

    public ByteWriter u8(int v) {
        ensure(1);
        buf[pos++] = (byte) v;
        return this;
    }

    public ByteWriter i8(int v) {
        if (v < Byte.MIN_VALUE || v > Byte.MAX_VALUE) {
            throw new IllegalArgumentException("i8 out of range: " + v);
        }
        return u8(v);
    }

    public ByteWriter u16(int v) {
        ensure(2);
        buf[pos++] = (byte) v;
        buf[pos++] = (byte) (v >>> 8);
        return this;
    }

    public ByteWriter i16(int v) {
        if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) {
            throw new IllegalArgumentException("i16 out of range: " + v);
        }
        return u16(v);
    }

    public ByteWriter i32(int v) {
        ensure(4);
        buf[pos++] = (byte) v;
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 24);
        return this;
    }

    /** Беззнаковое 32-битное значение, хранящееся в long. */
    public ByteWriter u32(long v) {
        return i32((int) v);
    }

    public ByteWriter bytes(byte[] src, int off, int len) {
        ensure(len);
        System.arraycopy(src, off, buf, pos, len);
        pos += len;
        return this;
    }
}
