package com.ugame.game.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import kcp.Kcp;

/**
 * Минимальный клиент, повторяющий поведение KcpClient из kcp2k (C#). Нужен, чтобы проверить
 * совместимость сервера без Unity: рукопожатие с cookie, надёжный и ненадёжный каналы, Disconnect.
 */
final class Kcp2kTestClient implements AutoCloseable {
    private final DatagramSocket socket;
    private final InetSocketAddress server;
    private final Kcp kcp;
    private final byte[] recvBuf = new byte[2048];

    int cookie; // 0, пока не пришёл Hello сервера
    boolean helloReceived;
    boolean disconnected;
    final List<byte[]> reliable = new ArrayList<>();
    final List<byte[]> unreliable = new ArrayList<>();

    Kcp2kTestClient(int port) throws IOException {
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(5);
        this.server = new InetSocketAddress("127.0.0.1", port);
        this.kcp = new Kcp(0, (buf, ignored) -> {
            buf.setByte(0, 1);           // channel = Reliable
            buf.setIntLE(1, cookie);     // cookie
            byte[] bytes = ByteBufUtil.getBytes(buf);
            buf.release();
            sendRaw(bytes);
        });
        kcp.setByteBufAllocator(ByteBufAllocator.DEFAULT);
        kcp.setMtu(1200);
        kcp.setReserved(5);
        kcp.nodelay(true, 10, 0, true);
        kcp.setSndWnd(32);
        kcp.setRcvWnd(128);
    }

    void sendHello() {
        sendReliable((byte) 1, new byte[0]);
    }

    void sendReliableData(byte[] payload) {
        sendReliable((byte) 3, payload);
    }

    void sendUnreliableData(byte[] payload) {
        byte[] packet = new byte[6 + payload.length];
        packet[0] = 2;
        writeIntLE(packet, 1, cookie);
        packet[5] = 4;
        System.arraycopy(payload, 0, packet, 6, payload.length);
        sendRaw(packet);
    }

    void sendDisconnect() {
        byte[] packet = new byte[6];
        packet[0] = 2;
        writeIntLE(packet, 1, cookie);
        packet[5] = 5;
        sendRaw(packet);
    }

    /** Сырой UDP-пакет — для проверок защиты (неверный cookie и т. п.). */
    void sendRaw(byte[] packet) {
        try {
            socket.send(new DatagramPacket(packet, packet.length, server));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void sendReliable(byte header, byte[] payload) {
        ByteBuf msg = Unpooled.buffer(1 + payload.length);
        msg.writeByte(header).writeBytes(payload);
        kcp.send(msg);
        msg.release();
        kcp.update(System.currentTimeMillis());
    }

    /** Крутит приём/update, пока условие не выполнится или не выйдет время. */
    boolean pollUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            receiveOnce();
            kcp.update(System.currentTimeMillis());
            drainReliable();
            if (condition.getAsBoolean()) {
                return true;
            }
        }
        return condition.getAsBoolean();
    }

    private void receiveOnce() throws IOException {
        DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
        try {
            socket.receive(packet);
        } catch (SocketTimeoutException e) {
            return;
        }
        int len = packet.getLength();
        if (len <= 5) {
            return;
        }
        int msgCookie = readIntLE(recvBuf, 1);
        if (cookie == 0) {
            cookie = msgCookie;
        }
        if (recvBuf[0] == 1) {
            ByteBuf body = Unpooled.copiedBuffer(recvBuf, 5, len - 5);
            kcp.input(body, true, System.currentTimeMillis());
            body.release();
        } else if (recvBuf[0] == 2) {
            byte header = recvBuf[5];
            if (header == 4) {
                byte[] data = new byte[len - 6];
                System.arraycopy(recvBuf, 6, data, 0, data.length);
                unreliable.add(data);
            } else if (header == 5) {
                disconnected = true;
            }
        }
    }

    private void drainReliable() {
        while (kcp.canRecv()) {
            ByteBuf msg = kcp.mergeRecv();
            if (msg == null) {
                return;
            }
            try {
                byte header = msg.readByte();
                switch (header) {
                    case 1 -> helloReceived = true;
                    case 3 -> reliable.add(ByteBufUtil.getBytes(msg));
                    case 2 -> { // Ping → Pong с тем же временем
                        byte[] ts = ByteBufUtil.getBytes(msg);
                        sendReliable((byte) 4, ts);
                    }
                    default -> { }
                }
            } finally {
                msg.release();
            }
        }
    }

    private static void writeIntLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    @Override
    public void close() {
        kcp.release();
        socket.close();
    }
}
