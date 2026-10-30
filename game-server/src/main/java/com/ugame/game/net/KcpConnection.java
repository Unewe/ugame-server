package com.ugame.game.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;
import java.net.InetSocketAddress;
import kcp.IKcp;
import kcp.Kcp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Одно соединение клиента. Повторяет протокол KcpPeer/KcpServerConnection из kcp2k:
 *
 * <pre>
 * UDP-пакет:   [u8 channel][u32 cookie LE][...]
 *   channel=1 (Reliable):   [...] = сегменты KCP (conv=0); внутри собранного KCP-сообщения:
 *                           [u8 KcpHeaderReliable: Hello=1, Ping=2, Data=3, Pong=4][payload]
 *   channel=2 (Unreliable): [...] = [u8 KcpHeaderUnreliable: Data=4, Disconnect=5][payload]
 * </pre>
 *
 * Рукопожатие: клиент шлёт Hello (cookie=0) → сервер переходит в Authenticated и отвечает Hello
 * со своим случайным cookie → дальше все пакеты клиента обязаны содержать этот cookie.
 *
 * <p>Потоки: всё состояние меняется только в event loop. Публичные методы {@link #send} и
 * {@link #close} потокобезопасны — они перекладывают работу в event loop.
 */
public final class KcpConnection {
    private static final Logger log = LoggerFactory.getLogger(KcpConnection.class);

    // KcpHeaderReliable (kcp2k)
    static final byte HDR_HELLO = 1;
    static final byte HDR_PING = 2;
    static final byte HDR_DATA = 3;
    static final byte HDR_PONG = 4;
    // KcpHeaderUnreliable (kcp2k)
    static final byte HDR_UNRELIABLE_DATA = 4;
    static final byte HDR_DISCONNECT = 5;

    static final int PING_INTERVAL_MS = 1000;
    /** kcp2k: QueueDisconnectThreshold — клиент не успевает принимать данные. */
    static final int QUEUE_DISCONNECT_THRESHOLD = 10_000;

    public enum State { CONNECTED, AUTHENTICATED, DISCONNECTED }

    private final KcpServer server;
    private final KcpConfig config;
    private final EventLoop eventLoop;
    private final ByteBufAllocator alloc;
    private final InetSocketAddress remote;
    private final int cookie;
    private final Kcp kcp;
    private final long createdAt;

    private State state = State.CONNECTED;
    private long lastReceiveTime;
    private long lastPingTime;
    private long lastPongTime;
    private volatile int rttMs;
    private volatile Object attachment;

    KcpConnection(KcpServer server, InetSocketAddress remote, int cookie, long now) {
        this.server = server;
        this.config = server.config();
        this.eventLoop = server.eventLoop();
        this.alloc = server.alloc();
        this.remote = remote;
        this.cookie = cookie;
        this.createdAt = now;
        this.lastReceiveTime = now;
        this.lastPingTime = now;

        this.kcp = new Kcp(0, this::onKcpOutput); // kcp2k всегда использует conv = 0
        kcp.setByteBufAllocator(alloc);
        kcp.setMtu(config.mtu());
        // Резервируем 5 байт в начале каждого исходящего KCP-пакета под [channel][cookie].
        // Получается то же, что SetMtu(Mtu - METADATA_SIZE_RELIABLE) в kcp2k.
        kcp.setReserved(KcpConfig.METADATA_SIZE);
        kcp.nodelay(config.noDelay(), config.intervalMs(), config.fastResend(), !config.congestionWindow());
        kcp.setSndWnd(config.sendWindow());
        kcp.setRcvWnd(config.receiveWindow());
    }

    public InetSocketAddress remoteAddress() {
        return remote;
    }

    public int rttMs() {
        return rttMs;
    }

    public boolean isAuthenticated() {
        return state == State.AUTHENTICATED;
    }

    public boolean isClosed() {
        return state == State.DISCONNECTED;
    }

    @SuppressWarnings("unchecked")
    public <T> T attachment() {
        return (T) attachment;
    }

    public void attach(Object value) {
        this.attachment = value;
    }

    // ------------------------------------------------------------------ отправка (любой поток)

    /** Отправить прикладное сообщение. Массив передаётся во владение транспорту — не меняйте его после вызова. */
    public void send(byte[] payload, KcpChannel channel) {
        send(Unpooled.wrappedBuffer(payload), channel);
    }

    /** Отправить прикладное сообщение. Буфер передаётся во владение транспорту (будет освобождён). */
    public void send(ByteBuf payload, KcpChannel channel) {
        if (eventLoop.inEventLoop()) {
            sendNow(payload, channel);
        } else {
            eventLoop.execute(() -> sendNow(payload, channel));
        }
    }

    /** Закрыть соединение с уведомлением клиента. */
    public void close(String reason) {
        if (eventLoop.inEventLoop()) {
            disconnect(reason, true);
        } else {
            eventLoop.execute(() -> disconnect(reason, true));
        }
    }

    /**
     * Закрыть с задержкой — чтобы успели уйти уже поставленные в очередь надёжные сообщения
     * (например, JoinResult с текстом ошибки).
     */
    public void close(String reason, long delayMs) {
        eventLoop.schedule(() -> disconnect(reason, true), delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void sendNow(ByteBuf payload, KcpChannel channel) {
        try {
            if (state != State.AUTHENTICATED) {
                return;
            }
            int len = payload.readableBytes();
            if (len == 0) {
                log.warn("{}: попытка отправить пустое сообщение", this);
                return;
            }
            if (channel == KcpChannel.RELIABLE) {
                if (len > config.reliableMaxMessageSize()) {
                    log.error("{}: reliable message of {} bytes exceeds the limit of {}", this, len, config.reliableMaxMessageSize());
                    return;
                }
                sendReliable(HDR_DATA, payload);
            } else {
                if (len > config.unreliableMaxMessageSize()) {
                    log.error("{}: unreliable message of {} bytes exceeds the limit of {}", this, len, config.unreliableMaxMessageSize());
                    return;
                }
                sendUnreliable(HDR_UNRELIABLE_DATA, payload);
            }
        } finally {
            payload.release();
        }
    }

    private void sendReliable(byte header, ByteBuf content) {
        ByteBuf msg = alloc.buffer(1 + (content == null ? 0 : content.readableBytes()));
        try {
            msg.writeByte(header);
            if (content != null) {
                msg.writeBytes(content, content.readerIndex(), content.readableBytes());
            }
            int ret = kcp.send(msg); // KCP берёт retained-срезы, наш буфер освобождаем сами
            if (ret < 0) {
                log.error("{}: kcp.send вернул {}", this, ret);
            }
        } finally {
            msg.release();
        }
    }

    /** Ping/Pong: заголовок + 4 байта времени (little-endian, как Utils.Encode32U в kcp2k). */
    private void sendReliableInt(byte header, int value) {
        ByteBuf msg = alloc.buffer(5);
        try {
            msg.writeByte(header).writeIntLE(value);
            int ret = kcp.send(msg);
            if (ret < 0) {
                log.error("{}: kcp.send вернул {}", this, ret);
            }
        } finally {
            msg.release();
        }
    }

    private void sendUnreliable(byte header, ByteBuf content) {
        int len = content == null ? 0 : content.readableBytes();
        ByteBuf packet = alloc.ioBuffer(KcpConfig.METADATA_SIZE + 1 + len);
        packet.writeByte(KcpChannel.UNRELIABLE.id);
        packet.writeIntLE(cookie);
        packet.writeByte(header);
        if (content != null) {
            packet.writeBytes(content, content.readerIndex(), len);
        }
        server.writeRaw(packet, remote, true);
    }

    /** Колбэк KCP: готов исходящий пакет. В начале зарезервировано METADATA_SIZE байт. */
    private void onKcpOutput(ByteBuf data, IKcp ignored) {
        data.setByte(0, KcpChannel.RELIABLE.id);
        data.setIntLE(1, cookie);
        server.writeRaw(data, remote, false); // сброс в сокет — в конце тика
    }

    // ------------------------------------------------------------------ приём (event loop)

    /** Сырой UDP-пакет от этого адреса. Буфер принадлежит вызывающему. */
    void rawInput(ByteBuf packet, long now) {
        if (state == State.DISCONNECTED) {
            return;
        }
        int idx = packet.readerIndex();
        int len = packet.readableBytes();
        if (len <= KcpConfig.METADATA_SIZE) {
            return;
        }
        byte channel = packet.getByte(idx);
        int messageCookie = packet.getIntLE(idx + 1);

        // Защита от подмены UDP-адреса: после рукопожатия cookie обязан совпадать.
        // Несовпадение — норма для повторно доставленного Hello, поэтому просто отбрасываем.
        if (state == State.AUTHENTICATED && messageCookie != cookie) {
            return;
        }

        ByteBuf body = packet.slice(idx + KcpConfig.METADATA_SIZE, len - KcpConfig.METADATA_SIZE);
        if (channel == KcpChannel.RELIABLE.id) {
            int ret = kcp.input(body, true, now);
            if (ret < 0) {
                log.debug("{}: kcp.input вернул {}", this, ret);
                return;
            }
            receiveReliable(now);
        } else if (channel == KcpChannel.UNRELIABLE.id) {
            inputUnreliable(body, now);
        }
        // иной канал — шум из интернета, игнорируем
    }

    private void inputUnreliable(ByteBuf body, long now) {
        if (!body.isReadable()) {
            return;
        }
        byte header = body.readByte();
        if (header == HDR_UNRELIABLE_DATA) {
            // До рукопожатия ненадёжные данные молча игнорируются (так же в kcp2k).
            if (state == State.AUTHENTICATED && body.isReadable()) {
                lastReceiveTime = now;
                server.listener().onData(this, body, KcpChannel.UNRELIABLE);
            }
        } else if (header == HDR_DISCONNECT) {
            disconnect("клиент отключился", false);
        } else {
            disconnect("неизвестный ненадёжный заголовок " + header, true);
        }
    }

    private void receiveReliable(long now) {
        while (state != State.DISCONNECTED && kcp.canRecv()) {
            ByteBuf msg = kcp.mergeRecv();
            if (msg == null) {
                return;
            }
            try {
                handleReliable(msg, now);
            } finally {
                msg.release();
            }
        }
    }

    private void handleReliable(ByteBuf msg, long now) {
        if (msg.readableBytes() > 1 + config.reliableMaxMessageSize()) {
            disconnect("слишком большое сообщение " + msg.readableBytes(), true);
            return;
        }
        if (!msg.isReadable()) {
            disconnect("пустое надёжное сообщение", true);
            return;
        }
        byte header = msg.readByte();
        lastReceiveTime = now;

        if (state == State.CONNECTED) {
            switch (header) {
                case HDR_HELLO -> {
                    state = State.AUTHENTICATED;
                    sendReliable(HDR_HELLO, null); // отвечаем Hello — клиент узнаёт cookie
                    server.listener().onConnected(this);
                }
                case HDR_PING, HDR_PONG -> { /* до рукопожатия игнорируем */ }
                default -> disconnect("данные до рукопожатия", true);
            }
            return;
        }

        switch (header) {
            case HDR_DATA -> {
                if (msg.isReadable()) {
                    server.listener().onData(this, msg, KcpChannel.RELIABLE);
                } else {
                    disconnect("пустое Data-сообщение", true);
                }
            }
            case HDR_PING -> {
                // Ping содержит время клиента; возвращаем его в Pong для расчёта RTT.
                // Не чаще раза в секунду, чтобы нельзя было «закидать» сервер пингами.
                if (msg.readableBytes() == 4 && now - lastPongTime >= PING_INTERVAL_MS) {
                    sendReliableInt(HDR_PONG, msg.readIntLE());
                    lastPongTime = now;
                }
            }
            case HDR_PONG -> {
                if (msg.readableBytes() == 4) {
                    int sent = msg.readIntLE();
                    int diff = localTime(now) - sent;
                    if (diff >= 0) {
                        rttMs = diff;
                    }
                }
            }
            case HDR_HELLO -> disconnect("повторный Hello", true);
            default -> disconnect("неизвестный надёжный заголовок " + header, true);
        }
    }

    // ------------------------------------------------------------------ тик (event loop)

    void tick(long now) {
        if (state == State.DISCONNECTED) {
            return;
        }
        if (now - lastReceiveTime >= config.timeoutMs()) {
            disconnect("таймаут: нет данных " + config.timeoutMs() + " мс", true);
            return;
        }
        if (kcp.waitSnd() >= QUEUE_DISCONNECT_THRESHOLD) {
            disconnect("клиент не успевает принимать данные", true);
            return;
        }
        if (state == State.AUTHENTICATED && now - lastPingTime >= PING_INTERVAL_MS) {
            sendReliableInt(HDR_PING, localTime(now));
            lastPingTime = now;
        }
        kcp.update(now);
    }

    private int localTime(long now) {
        return (int) (now - createdAt);
    }

    void disconnect(String reason, boolean notifyRemote) {
        if (state == State.DISCONNECTED) {
            return;
        }
        boolean wasAuthenticated = state == State.AUTHENTICATED;
        if (notifyRemote && wasAuthenticated) {
            // Как в kcp2k: несколько ненадёжных Disconnect подряд — дойдёт хотя бы один.
            for (int i = 0; i < 5; i++) {
                sendUnreliable(HDR_DISCONNECT, null);
            }
        }
        state = State.DISCONNECTED;
        kcp.release();
        server.onClosed(this);
        if (wasAuthenticated) {
            log.debug("{}: отключён ({})", this, reason);
            server.listener().onDisconnected(this, reason);
        }
    }

    /** Освобождение соединения, не прошедшего рукопожатие (без уведомлений). */
    void discard() {
        if (state != State.DISCONNECTED) {
            state = State.DISCONNECTED;
            kcp.release();
        }
    }

    @Override
    public String toString() {
        return "KcpConnection[" + remote + "]";
    }
}
