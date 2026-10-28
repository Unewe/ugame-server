package com.ugame.protocol;

/** Квантование чисел с плавающей точкой для компактной передачи по сети. */
public final class Quantize {
    private Quantize() {}

    /** Позиция в метрах → сантиметры в i16 (диапазон ±327.67 м, точность 1 см). */
    public static int posToI16(float meters) {
        int v = Math.round(meters * 100f);
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    public static float i16ToPos(int v) {
        return v / 100f;
    }

    /** Ось джойстика [-1..1] → i8 [-127..127]. */
    public static int axisToI8(float axis) {
        float a = Math.max(-1f, Math.min(1f, axis));
        return Math.round(a * 127f);
    }

    public static float i8ToAxis(int v) {
        return Math.max(-1f, Math.min(1f, v / 127f));
    }

    /** Угол в радианах → u16 (0..65535 соответствует 0..2π). */
    public static int angleToU16(float radians) {
        double twoPi = Math.PI * 2;
        double a = radians % twoPi;
        if (a < 0) {
            a += twoPi;
        }
        return (int) Math.round(a / twoPi * 65536.0) & 0xFFFF;
    }

    public static float u16ToAngle(int v) {
        return (float) ((v & 0xFFFF) / 65536.0 * Math.PI * 2);
    }

    /** Сравнение 16-битных номеров с учётом переполнения: true, если a «новее» b. */
    public static boolean seqGreater(int a, int b) {
        int diff = (a - b) & 0xFFFF;
        return diff != 0 && diff < 0x8000;
    }
}
