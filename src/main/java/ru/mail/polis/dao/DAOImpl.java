package ru.mail.polis.dao;

import org.jetbrains.annotations.NotNull;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import ru.mail.polis.Record;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;

public final class DAOImpl implements DAO {
    private final RocksDB db;

    public DAOImpl(final RocksDB db) {
        this.db = db;
    }

    @NotNull
    @Override
    public Iterator<Record> iterator(@NotNull final ByteBuffer from) throws IOException {
        final RocksIterator iter = db.newIterator();
        final byte[] packedKey = ByteArrayUtils.packingKey(from);
        iter.seek(packedKey);
        return new RecordIterator(iter);
    }

    @NotNull
    @Override
    public ByteBuffer get(@NotNull final ByteBuffer key) throws IOException, NoSuchElementException {
        final DAORecord record = getRecord(key);
        if (record.isDeleted()) {
            throw new NoSuchElementException();
        }
        return record.getValue();
    }

    @Override
    public void upsert(@NotNull final ByteBuffer key, @NotNull final ByteBuffer value) throws IOException {
        upsertRecord(key, new DAORecord(value, System.currentTimeMillis(), false));
    }

    @Override
    public void remove(@NotNull final ByteBuffer key) throws IOException {
        upsertRecord(key, new DAORecord(ByteBuffer.allocate(0), System.currentTimeMillis(), true));
    }

    @Override
    public void compact() throws IOException {
        try {
            db.compactRange();
        } catch (RocksDBException e) {
            throw new IOException("could not compact", e);
        }
    }

    @NotNull
    @Override
    public DAORecord getRecord(@NotNull final ByteBuffer key) throws IOException, NoSuchElementException {
        try {
            final byte[] packedKey = ByteArrayUtils.packingKey(key);
            final byte[] resOfGet = db.get(packedKey);
            if (resOfGet == null) {
                throw new NoSuchElementException("get returned null");
            }
            return DAORecord.fromBytes(resOfGet);
        } catch (RocksDBException e) {
            throw new IOException("could not get", e);
        }
    }

    @Override
    public void upsertRecord(@NotNull final ByteBuffer key, @NotNull final DAORecord value) throws IOException {
        try {
            final byte[] packedKey = ByteArrayUtils.packingKey(key);
            final byte[] arrayValue = value.toBytes();
            db.put(packedKey, arrayValue);
        } catch (RocksDBException e) {
            throw new IOException("could not put", e);
        }
    }

    @Override
    public void close() throws IOException {
        db.close();
    }
}
