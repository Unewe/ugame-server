package com.ugame.game;

import com.google.protobuf.MessageLite;

/** Кодирование надёжных сообщений: [u8 MessageType][protobuf]. */
public final class ProtoMessages {
    private ProtoMessages() {}

    public static byte[] encode(byte type, MessageLite message) {
        int size = message.getSerializedSize();
        byte[] out = new byte[1 + size];
        out[0] = type;
        com.google.protobuf.CodedOutputStream cos = com.google.protobuf.CodedOutputStream.newInstance(out, 1, size);
        try {
            message.writeTo(cos);
            cos.checkNoSpaceLeft();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e); // запись в массив не бросает IOException
        }
        return out;
    }
}
