package com.ugame.game;

import com.google.protobuf.InvalidProtocolBufferException;
import com.ugame.game.net.KcpChannel;
import com.ugame.game.net.KcpConnection;
import com.ugame.game.net.KcpListener;
import com.ugame.game.redis.RedisGateway;
import com.ugame.game.room.ClientSession;
import com.ugame.game.room.RoomManager;
import com.ugame.protocol.ByteReader;
import com.ugame.protocol.InputPacket;
import com.ugame.protocol.MessageType;
import com.ugame.protocol.pb.JoinRequest;
import com.ugame.protocol.pb.JoinResult;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Связка транспорта и игры: декодирует сообщения в потоке event loop и передаёт их в комнаты.
 * Никакой игровой логики и блокирующих вызовов здесь нет.
 */
final class GameNetHandler implements KcpListener {
    private static final Logger log = LoggerFactory.getLogger(GameNetHandler.class);

    private final RoomManager rooms;
    private final RedisGateway redis;
    private final ByteReader reader = new ByteReader(); // только поток event loop

    GameNetHandler(RoomManager rooms, RedisGateway redis) {
        this.rooms = rooms;
        this.redis = redis;
    }

    @Override
    public void onConnected(KcpConnection connection) {
        connection.attach(new ClientSession(connection));
        log.debug("{}: handshake completed", connection);
    }

    @Override
    public void onData(KcpConnection connection, ByteBuf payload, KcpChannel channel) {
        ClientSession session = connection.attachment();
        byte type = payload.readByte();
        try {
            if (channel == KcpChannel.RELIABLE) {
                onReliable(session, type, payload);
            } else {
                onUnreliable(session, type, payload);
            }
        } catch (InvalidProtocolBufferException | ByteReader.MalformedPacketException e) {
            log.debug("{}: malformed message of type {}: {}", connection, type, e.getMessage());
            connection.close("protocol: corrupted message");
        }
    }

    private void onReliable(ClientSession session, byte type, ByteBuf payload) throws InvalidProtocolBufferException {
        switch (type) {
            case MessageType.C2S_JOIN -> handleJoin(session, JoinRequest.parseFrom(payload.nioBuffer()));
            default -> session.connection().close("protocol: unexpected type" + type);
        }
    }

    private void onUnreliable(ClientSession session, byte type, ByteBuf payload) {
        if (type == MessageType.C2S_INPUT) {
            if (session.room() == null) {
                return; // ввод до входа в комнату — игнорируем
            }
            byte[] bytes = ByteBufUtil.getBytes(payload);
            rooms.input(session, InputPacket.readBody(reader.wrap(bytes, 0, bytes.length)));
        }
        // неизвестные ненадёжные пакеты молча отбрасываем
    }

    private void handleJoin(ClientSession session, JoinRequest request) {
        if (!session.markJoinRequested()) {
            return;
        }
        redis.redeemTicket(request.getTicket()).whenComplete((ticket, error) -> {
            if (error != null || ticket == null) {
                if (error != null) {
                    log.warn("{}: ticket validation error: {}", session.connection(), error.toString());
                }
                session.send(ProtoMessages.encode(MessageType.S2C_JOIN_RESULT, JoinResult.newBuilder()
                        .setOk(false)
                        .setError(error != null ? "server_error" : "invalid_ticket")
                        .build()), KcpChannel.RELIABLE);
                session.connection().close("invalid ticket", 1000);
                return;
            }
            if (session.connection().isClosed()) {
                return; // клиент ушёл, пока мы ходили в Redis
            }
            rooms.join(session, ticket);
        });
    }

    @Override
    public void onDisconnected(KcpConnection connection, String reason) {
        ClientSession session = connection.attachment();
        if (session != null) {
            rooms.leave(session);
        }
        log.debug("{}: disabled: {}", connection, reason);
    }
}
