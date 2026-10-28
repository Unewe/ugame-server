package com.ugame.game.net;

/** Канал доставки — первый байт каждого UDP-пакета kcp2k. */
public enum KcpChannel {
    RELIABLE((byte) 1),
    UNRELIABLE((byte) 2);

    public final byte id;

    KcpChannel(byte id) {
        this.id = id;
    }
}
