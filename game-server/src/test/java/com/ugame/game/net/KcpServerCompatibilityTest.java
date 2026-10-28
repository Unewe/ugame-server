package com.ugame.game.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Сервер против клиента, ведущего себя как kcp2k: эхо по обоим каналам. */
class KcpServerCompatibilityTest {

    private KcpServer server;
    private final AtomicInteger connected = new AtomicInteger();
    private final CopyOnWriteArrayList<String> disconnects = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        KcpConfig cfg = KcpConfig.defaults(0); // порт выберет ОС
        cfg = new KcpConfig("127.0.0.1", 0, cfg.mtu(), cfg.noDelay(), cfg.intervalMs(), cfg.fastResend(),
                cfg.congestionWindow(), cfg.sendWindow(), cfg.receiveWindow(), 2_000, cfg.maxConnections(),
                cfg.socketBufferBytes());
        server = new KcpServer(cfg, new KcpListener() {
            @Override
            public void onConnected(KcpConnection connection) {
                connected.incrementAndGet();
            }

            @Override
            public void onData(KcpConnection connection, ByteBuf payload, KcpChannel channel) {
                connection.send(ByteBufUtil.getBytes(payload), channel); // эхо
            }

            @Override
            public void onDisconnected(KcpConnection connection, String reason) {
                disconnects.add(reason);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void handshakeAndEcho() throws Exception {
        try (Kcp2kTestClient client = new Kcp2kTestClient(server.localPort())) {
            client.sendHello();
            assertTrue(client.pollUntil(() -> client.helloReceived, 2_000), "сервер не ответил Hello");
            assertNotEquals(0, client.cookie, "сервер должен прислать ненулевой cookie");
            assertEquals(1, connected.get());

            byte[] small = {10, 20, 30};
            client.sendReliableData(small);
            assertTrue(client.pollUntil(() -> client.reliable.size() == 1, 2_000));
            assertArrayEquals(small, client.reliable.get(0));

            // большое надёжное сообщение — проверяем фрагментацию KCP
            byte[] big = new byte[20_000];
            for (int i = 0; i < big.length; i++) {
                big[i] = (byte) (i * 31);
            }
            client.sendReliableData(big);
            assertTrue(client.pollUntil(() -> client.reliable.size() == 2, 3_000));
            assertArrayEquals(big, client.reliable.get(1));

            byte[] input = {20, 1, 2, 3};
            client.sendUnreliableData(input);
            assertTrue(client.pollUntil(() -> !client.unreliable.isEmpty(), 2_000));
            assertArrayEquals(input, client.unreliable.get(0));
        }
    }

    @Test
    void packetsWithWrongCookieAreIgnored() throws Exception {
        try (Kcp2kTestClient client = new Kcp2kTestClient(server.localPort())) {
            client.sendHello();
            assertTrue(client.pollUntil(() -> client.helloReceived, 2_000));

            int realCookie = client.cookie;
            client.cookie = realCookie + 1;
            client.sendUnreliableData(new byte[] {1, 2, 3});
            client.pollUntil(() -> false, 300);
            assertTrue(client.unreliable.isEmpty(), "пакет с чужим cookie не должен обрабатываться");
            client.cookie = realCookie;
        }
    }

    @Test
    void dataBeforeHelloDoesNotCreateConnection() throws Exception {
        try (Kcp2kTestClient client = new Kcp2kTestClient(server.localPort())) {
            client.sendUnreliableData(new byte[] {1});
            client.sendRaw(new byte[] {1, 0, 0, 0, 0, 1, 2, 3}); // мусор в надёжном канале
            client.pollUntil(() -> false, 200);
            assertEquals(0, connected.get());
            assertEquals(0, server.connectionCount());
        }
    }

    @Test
    void clientDisconnectAndTimeout() throws Exception {
        try (Kcp2kTestClient client = new Kcp2kTestClient(server.localPort())) {
            client.sendHello();
            assertTrue(client.pollUntil(() -> client.helloReceived, 2_000));
            client.sendDisconnect();
            assertTrue(waitFor(() -> disconnects.size() == 1, 2_000));
        }
        try (Kcp2kTestClient client = new Kcp2kTestClient(server.localPort())) {
            client.sendHello();
            assertTrue(client.pollUntil(() -> client.helloReceived, 2_000));
            // клиент замолкает — таймаут в тесте 2 секунды
            assertTrue(waitFor(() -> disconnects.size() == 2, 4_000), "сервер должен отключить по таймауту");
            assertFalse(disconnects.get(1).isEmpty());
        }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
