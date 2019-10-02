package ru.mail.polis.dao;

import org.rocksdb.RocksIterator;
import ru.mail.polis.Record;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;

public class RecordIterator implements Iterator<Record>, Closeable {
    private final RocksIterator rocksIterator;

    public RecordIterator(RocksIterator rocksIterator) {
        this.rocksIterator = rocksIterator;
    }

    @Override
    public boolean hasNext() {
        return rocksIterator.isValid();
    }

    @Override
    public Record next() {
        if (!rocksIterator.isValid()) {
            throw new IllegalStateException("iterator is not valid");
        }
        byte[] key = rocksIterator.key();
        byte[] value = rocksIterator.value();
        Record record = Record.of(ByteBuffer.wrap(key), ByteBuffer.wrap(value));
        rocksIterator.next();
        return record;
    }


    @Override
    public void close() throws IOException {
        rocksIterator.close();
    }
}
