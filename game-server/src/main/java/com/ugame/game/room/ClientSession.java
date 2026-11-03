package com.ugame.game.room;

import com.ugame.game.net.KcpChannel;
import com.ugame.game.net.KcpConnection;
import com.ugame.protocol.InputPacket;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Игрок, подключённый к игровому серверу. Привязан к KcpConnection через attachment. */
public final class ClientSession {
    private final KcpConnection connection;
    private final AtomicBoolean joinRequested = new AtomicBoolean();
    private volatile Room room;

    // ниже — только поток комнаты
    long playerId;
    String username;
    Brawler brawler;
    private long ackedSnapshotTick = -1; // последний снапшот, который клиент подтвердил; -1 — ни одного

    public ClientSession(KcpConnection connection) {
        this.connection = connection;
    }

    public KcpConnection connection() {
        return connection;
    }

    public Room room() {
        return room;
    }

    void bindRoom(Room room) {
        this.room = room;
    }

    /** true только для первого JoinRequest — повторные игнорируются. */
    public boolean markJoinRequested() {
        return joinRequested.compareAndSet(false, true);
    }

    public void send(byte[] message, KcpChannel channel) {
        connection.send(message, channel);
    }

    long ackedSnapshotTick() {
        return ackedSnapshotTick;
    }

    /** Клиент получил снапшот этого тика (0 — ещё ни одного). Пакеты могут прийти не по порядку. */
    void acknowledgeSnapshot(long tick) {
        if (tick > 0 && tick > ackedSnapshotTick) {
            ackedSnapshotTick = tick;
        }
    }

    /**
     * Поставить новые команды в очередь бойца (пакет содержит их от новой к старой; дубли World
     * отбросит сам). Выполняются они по одной за тик в {@link World#step()}. Поток комнаты.
     */
    void applyInputs(World world, List<InputPacket.Command> newestFirst) {
        if (brawler == null) {
            return;
        }
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            world.queueInput(brawler, newestFirst.get(i));
        }
    }
}
