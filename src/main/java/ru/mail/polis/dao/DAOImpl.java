package ru.mail.polis.dao;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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

    public DAOImpl(RocksDB db) {
        this.db = db;
    }

    @NotNull
    @Override
    public Iterator<Record> iterator(@NotNull ByteBuffer from) throws IOException {
        RocksIterator iter = db.newIterator();
        iter.seek(from.array());
        return new RecordIterator(iter);
    }

    @NotNull
    @Override
    public ByteBuffer get(@NotNull ByteBuffer key) throws IOException, NoSuchElementException {
        try {
            byte[] resOfGet = db.get(key.array());
            if (resOfGet == null) {
                throw new NoSuchElementException("get returned null");
            }
            return ByteBuffer.wrap(resOfGet);
        } catch (RocksDBException e) {
            throw new IOException("could not get", e);
        }
    }

    @Override
    public void upsert(@NotNull ByteBuffer key, @NotNull ByteBuffer value) throws IOException {
        try {
            db.put(key.array(), value.array());
        } catch (RocksDBException e) {
            throw new IOException("could not put", e);
        }
    }

    @Override
    public void remove(@NotNull ByteBuffer key) throws IOException {
        try {
            db.delete(key.array());
        } catch (RocksDBException e) {
            throw new IOException("could not delete", e);
        }
    }

    @Override
    public void compact() throws IOException {
        try {
            db.compactRange();
        } catch (RocksDBException e) {
            throw new IOException("could not compact", e);
        }
    }

    @Override
    public void close() throws IOException {
        db.close();
    }
}
