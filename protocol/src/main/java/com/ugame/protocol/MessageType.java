package com.ugame.protocol;

/**
 * Первый байт каждого прикладного сообщения (внутри Data-сообщения kcp2k).
 *
 * <p>Значения должны совпадать с MessageType.cs в Unity-клиенте.
 *
 * <ul>
 *   <li>1..19 — надёжный канал, полезная нагрузка — Protobuf (game.proto).</li>
 *   <li>20..39 — ненадёжный канал, ручная бинарная упаковка (little-endian).</li>
 * </ul>
 */
public final class MessageType {
    private MessageType() {}

    // --- reliable, protobuf ---
    public static final byte C2S_JOIN = 1;        // JoinRequest
    public static final byte S2C_JOIN_RESULT = 2; // JoinResult
    public static final byte S2C_MATCH_START = 3; // MatchStart
    public static final byte S2C_MATCH_END = 4;   // MatchEnd
    public static final byte S2C_KICK = 5;        // Kick

    // --- unreliable, ручная упаковка ---
    public static final byte C2S_INPUT = 20;      // InputPacket
    public static final byte S2C_SNAPSHOT = 21;   // SnapshotPacket

    public static boolean isReliable(byte type) {
        return type >= 1 && type < 20;
    }
}
