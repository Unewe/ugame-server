package com.ugame.game.net;

/**
 * Параметры транспорта. Значения по умолчанию совпадают с KcpConfig из kcp2k —
 * клиент и сервер должны использовать одинаковые MTU и окна.
 *
 * @param mtu               размер UDP-пакета целиком (kcp2k: Kcp.MTU_DEF = 1200)
 * @param noDelay           режим nodelay (kcp2k: true)
 * @param intervalMs        интервал kcp.update и тика транспорта (kcp2k: 10)
 * @param fastResend        быстрая переотправка (kcp2k: 0 — выключена)
 * @param congestionWindow  окно перегрузки (kcp2k: false)
 * @param sendWindow        окно отправки (kcp2k: Kcp.WND_SND = 32)
 * @param receiveWindow     окно приёма (kcp2k: Kcp.WND_RCV = 128)
 * @param timeoutMs         отключение, если от клиента ничего не приходило (kcp2k: 10000)
 */
public record KcpConfig(
        String host,
        int port,
        int mtu,
        boolean noDelay,
        int intervalMs,
        int fastResend,
        boolean congestionWindow,
        int sendWindow,
        int receiveWindow,
        int timeoutMs,
        int maxConnections,
        int socketBufferBytes) {

    /** 1 байт канала + 4 байта cookie перед каждым UDP-пакетом kcp2k. */
    public static final int METADATA_SIZE = 5;
    /** Заголовок сегмента KCP. */
    public static final int KCP_OVERHEAD = 24;
    /** kcp2k кодирует номер фрагмента одним байтом. */
    public static final int FRG_MAX = 255;

    public static KcpConfig defaults(int port) {
        return new KcpConfig("0.0.0.0", port, 1200, true, 10, 0, false, 32, 128, 10_000, 5_000, 4 * 1024 * 1024);
    }

    /** Максимум полезной нагрузки ненадёжного сообщения: MTU − канал − cookie − заголовок kcp2k. */
    public int unreliableMaxMessageSize() {
        return mtu - METADATA_SIZE - 1;
    }

    /**
     * Максимум надёжного сообщения (формула из KcpPeer.ReliableMaxMessageSize в kcp2k).
     * Считается по окну приёма клиента, поэтому receiveWindow должен совпадать с клиентом.
     */
    public int reliableMaxMessageSize() {
        int wnd = Math.min(receiveWindow, FRG_MAX);
        return (mtu - KCP_OVERHEAD - METADATA_SIZE) * (wnd - 1) - 1;
    }
}
