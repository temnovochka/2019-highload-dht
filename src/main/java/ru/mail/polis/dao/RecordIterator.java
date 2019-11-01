package ru.mail.polis.dao;

import org.rocksdb.RocksIterator;
import ru.mail.polis.Record;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;

public class RecordIterator implements Iterator<Record>, Closeable {
    private final RocksIterator rocksIterator;

    public RecordIterator(final RocksIterator rocksIterator) {
        this.rocksIterator = rocksIterator;
    }

    @Override
    public boolean hasNext() {
        skip();
        return rocksIterator.isValid();
    }

    private void skip() {
        while (this.rocksIterator.isValid()) {
            final byte[] value = this.rocksIterator.value();
            final DAORecord daoRecord = DAORecord.fromBytes(value);
            if (!daoRecord.isDeleted()) {
                break;
            }
            this.rocksIterator.next();
        }
    }

    @Override
    public Record next() {
        skip();
        if (!rocksIterator.isValid()) {
            throw new IllegalStateException("iterator is not valid");
        }
        final byte[] key = rocksIterator.key();
        final ByteBuffer unpackedKey = ByteArrayUtils.unpackingKey(key);
        final byte[] value = rocksIterator.value();
        final DAORecord daoRecord = DAORecord.fromBytes(value);
        final Record record = Record.of(unpackedKey, daoRecord.getValue());
        rocksIterator.next();
        skip();
        return record;
    }

    @Override
    public void close() throws IOException {
        rocksIterator.close();
    }
}
