package ru.mail.polis.dao;

import java.nio.ByteBuffer;

public class DAORecord {
    private final ByteBuffer value;
    private final long timestamp;
    private final boolean deleted;

    /**
     * Constructor for DAORecord.
     *
     * @param value     - value of record
     * @param timestamp - time in milliseconds when record was last updated
     * @param deleted   - flag for records if there are deleted or not
     */
    public DAORecord(final ByteBuffer value, final long timestamp, final boolean deleted) {
        this.value = value;
        this.timestamp = timestamp;
        this.deleted = deleted;
    }

    /**
     * Makes bytes from DAORecord.
     *
     * @return bytes
     */
    public byte[] toBytes() {
        final char deleted = this.deleted ? 'd' : 'e';
        return ByteBuffer.allocate(Character.BYTES + Long.BYTES + value.remaining())
                .putChar(deleted).putLong(timestamp).put(value.duplicate()).array();
    }

    /**
     * Make DAORecord from bytes.
     *
     * @param bytes for making DAORecord from
     * @return constructed DAORecord
     */
    public static DAORecord fromBytes(final byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        final char symbol = buffer.getChar();
        final long timestamp = buffer.getLong();
        return new DAORecord(buffer, timestamp, symbol == 'd');
    }

    /**
     * Check if record is deleted.
     *
     * @return true if deleted, false if not deleted
     */
    public boolean isDeleted() {
        return deleted;
    }

    /**
     * Get value from DAORecord.
     *
     * @return value of record
     */
    public ByteBuffer getValue() {
        if (this.isDeleted()) {
            throw new IllegalStateException("Record is deleted");
        }
        return value;
    }

    /**
     * Get timestamp of the DAORecord.
     *
     * @return timestamp in milliseconds
     */
    public long getTimestamp() {
        return timestamp;
    }
}
