package ru.mail.polis.dao;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class ByteArrayUtils {
    private ByteArrayUtils() {
    }


    /**
     * Make byte[] from ByteBuffer.
     *
     * @param buffer - ByteBuffer to make array from
     * @return byte[] from buffer
     */
    public static byte[] getArrayFromByteBuffer(@NotNull final ByteBuffer buffer) {
        final ByteBuffer copy = buffer.duplicate();
        final byte[] array = new byte[copy.remaining()];
        copy.get(array);
        return array;
    }

    /**
     * Take array from ByteBuffer, move it on Byte.MIN_VALUE up.
     *
     * @param key - ByteBuffer to make moved array from
     * @return moved on Byte.MIN_VALUE up byte[]
     */
    public static byte[] packingKey(@NotNull final ByteBuffer key) {
        final byte[] arrayKey = getArrayFromByteBuffer(key);
        for (int i = 0; i < arrayKey.length; i++) {
            arrayKey[i] -= Byte.MIN_VALUE;
        }
        return arrayKey;
    }

    /**
     * Move array on Byte.MIN_VALUE back.
     *
     * @param key - array to move
     * @return moved ByteBuffer
     */
    public static ByteBuffer unpackingKey(@NotNull final byte[] key) {
        final byte[] copy = Arrays.copyOf(key, key.length);
        for (int i = 0; i < copy.length; i++) {
            copy[i] += Byte.MIN_VALUE;
        }
        return ByteBuffer.wrap(copy);
    }
}
