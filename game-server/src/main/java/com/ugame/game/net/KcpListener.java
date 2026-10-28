package com.ugame.game.net;

import io.netty.buffer.ByteBuf;

/**
 * События транспорта. Все методы вызываются в потоке event loop Netty — держите их короткими:
 * декодируйте сообщение и передайте его дальше (в очередь комнаты), не выполняйте игровую логику здесь.
 */
public interface KcpListener {

    /** Клиент прошёл рукопожатие kcp2k (Hello). */
    void onConnected(KcpConnection connection);

    /**
     * Пришли прикладные данные. {@code payload} валиден только во время вызова и будет освобождён
     * транспортом — не сохраняйте ссылку, скопируйте байты, если они нужны позже.
     */
    void onData(KcpConnection connection, ByteBuf payload, KcpChannel channel);

    /** Соединение закрыто (таймаут, Disconnect от клиента, ошибка протокола, остановка сервера). */
    void onDisconnected(KcpConnection connection, String reason);
}
