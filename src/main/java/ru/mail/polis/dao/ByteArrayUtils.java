package ru.mail.polis.dao;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class ByteArrayUtils {
    private ByteArrayUtils() {};

    public static byte[] getArrayFromByteBuffer(@NotNull final ByteBuffer buffer){
        final ByteBuffer copy = buffer.duplicate();
        final byte[] array = new byte[copy.remaining()];
        copy.get(array);
        return array;
    }

    public static byte[] packingKey(@NotNull final ByteBuffer key) {
        final byte[] arrayKey = getArrayFromByteBuffer(key);
        for (int i = 0; i < arrayKey.length; i++) {
            arrayKey[i] -= Byte.MIN_VALUE;
        }
        return arrayKey;
    }

    public static ByteBuffer unpackingKey(@NotNull final byte[] key) {
        final byte[] copy = Arrays.copyOf(key, key.length);
        for (int i = 0; i < copy.length; i++) {
            copy[i] += Byte.MIN_VALUE;
        }
        return ByteBuffer.wrap(copy);
    }
}
