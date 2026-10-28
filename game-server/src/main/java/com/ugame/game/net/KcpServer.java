package com.ugame.game.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UDP-сервер, совместимый с kcp2k (KcpServer из Mirror/kcp2k).
 *
 * <p>Один сокет, один поток event loop: приём пакетов, KCP update и отправка идут в одном потоке,
 * поэтому синхронизация внутри транспорта не нужна. Одного потока хватает на тысячи соединений;
 * игровая логика работает в отдельных потоках (см. RoomManager).
 */
public final class KcpServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KcpServer.class);

    private final KcpConfig config;
    private final KcpListener listener;
    private final SecureRandom random = new SecureRandom();

    // доступ только из event loop
    private final Map<InetSocketAddress, KcpConnection> connections = new HashMap<>();
    private final List<KcpConnection> closed = new ArrayList<>();

    private EventLoopGroup group;
    private Channel channel;
    private ScheduledFuture<?> tickTask;

    public KcpServer(KcpConfig config, KcpListener listener) {
        this.config = config;
        this.listener = listener;
    }

    public void start() throws InterruptedException {
        boolean epoll = Epoll.isAvailable();
        group = epoll ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);

        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(epoll ? EpollDatagramChannel.class : NioDatagramChannel.class)
                .option(ChannelOption.SO_RCVBUF, config.socketBufferBytes())
                .option(ChannelOption.SO_SNDBUF, config.socketBufferBytes())
                .handler(new InboundHandler());

        channel = bootstrap.bind(config.host(), config.port()).sync().channel();
        tickTask = channel.eventLoop().scheduleAtFixedRate(
                this::tick, config.intervalMs(), config.intervalMs(), TimeUnit.MILLISECONDS);

        log.info("KCP-сервер слушает udp://{}:{} (транспорт: {})", config.host(), config.port(), epoll ? "epoll" : "nio");
    }

    KcpConfig config() {
        return config;
    }

    KcpListener listener() {
        return listener;
    }

    EventLoop eventLoop() {
        return channel.eventLoop();
    }

    ByteBufAllocator alloc() {
        return channel.alloc();
    }

    /** Фактический порт (полезно, если в конфиге 0 — тогда порт выбирает ОС). */
    public int localPort() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    /** Число соединений (приблизительно, читается из другого потока). */
    public int connectionCount() {
        return connections.size();
    }

    // ------------------------------------------------------------------ event loop

    private void onDatagram(ByteBuf buf, InetSocketAddress sender) {
        long now = System.currentTimeMillis();
        KcpConnection connection = connections.get(sender);

        if (connection == null) {
            // Новый адрес. Как в kcp2k: соединение регистрируется только если первый же пакет
            // содержит рукопожатие Hello. Иначе это шум или «хвост» старой сессии.
            if (connections.size() >= config.maxConnections()) {
                return;
            }
            if (buf.readableBytes() <= KcpConfig.METADATA_SIZE
                    || buf.getByte(buf.readerIndex()) != KcpChannel.RELIABLE.id) {
                return;
            }
            KcpConnection candidate = new KcpConnection(this, sender, newCookie(), now);
            connections.put(sender, candidate); // временно, чтобы onConnected видел соединение
            candidate.rawInput(buf, now);
            if (!candidate.isAuthenticated()) {
                connections.remove(sender, candidate);
                candidate.discard();
            }
        } else {
            connection.rawInput(buf, now);
        }
        removeClosed();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        try {
            for (KcpConnection connection : connections.values()) {
                connection.tick(now);
            }
        } catch (RuntimeException e) {
            log.error("Ошибка в тике транспорта", e);
        }
        removeClosed();
        channel.flush();
    }

    /** Вызывается соединением при закрытии. Удаление откладывается — мы можем быть внутри обхода. */
    void onClosed(KcpConnection connection) {
        closed.add(connection);
    }

    private void removeClosed() {
        if (closed.isEmpty()) {
            return;
        }
        for (KcpConnection c : closed) {
            connections.remove(c.remoteAddress(), c);
        }
        closed.clear();
    }

    /** Запись в сокет. Буфер передаётся во владение Netty. */
    void writeRaw(ByteBuf data, InetSocketAddress to, boolean flushNow) {
        DatagramPacket packet = new DatagramPacket(data, to);
        if (flushNow) {
            channel.writeAndFlush(packet, channel.voidPromise());
        } else {
            channel.write(packet, channel.voidPromise());
        }
    }

    private int newCookie() {
        int cookie;
        do {
            cookie = random.nextInt();
        } while (cookie == 0); // 0 у kcp2k-клиента означает «cookie ещё не получен»
        return cookie;
    }

    // ------------------------------------------------------------------ остановка

    @Override
    public void close() {
        if (channel == null) {
            return;
        }
        try {
            channel.eventLoop().submit(() -> {
                if (tickTask != null) {
                    tickTask.cancel(false);
                }
                for (KcpConnection c : new ArrayList<>(connections.values())) {
                    c.disconnect("сервер остановлен", true);
                }
                removeClosed();
                channel.flush();
            }).sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        channel.close().awaitUninterruptibly();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly();
        log.info("KCP-сервер остановлен");
    }

    private final class InboundHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            try {
                onDatagram(packet.content(), packet.sender());
            } catch (RuntimeException e) {
                log.warn("Ошибка обработки пакета от {}", packet.sender(), e);
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("Ошибка сокета", cause); // UDP-сокет не закрываем из-за единичных ошибок
        }
    }
}
