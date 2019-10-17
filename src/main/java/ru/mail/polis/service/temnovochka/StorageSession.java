package ru.mail.polis.service.temnovochka;

import com.google.common.base.Charsets;
import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Response;
import one.nio.net.Socket;
import ru.mail.polis.Record;
import ru.mail.polis.dao.ByteArrayUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;

public class StorageSession extends HttpSession {
    private Iterator<Record> iter;

    StorageSession(final Socket socket, final HttpServer server) {
        super(socket, server);
    }

    public void stream(final Iterator<Record> iter) throws IOException {
        this.iter = iter;

        final Response response = new Response(Response.OK);
        response.addHeader("Transfer-Encoding: chunked");
        writeResponse(response, false);

        next();
    }

    private byte[] makeChunk(final Record record) {
        final byte[] key = ByteArrayUtils.getArrayFromByteBuffer(record.getKey());
        final byte[] value = ByteArrayUtils.getArrayFromByteBuffer(record.getValue());

        final byte[] rn = "\r\n".getBytes(Charsets.UTF_8);
        final byte[] n = "\n".getBytes(Charsets.UTF_8);

        final int size = key.length + value.length + n.length;
        final byte[] hexSize = Integer.toHexString(size).getBytes(Charsets.UTF_8);
        final int len = size + hexSize.length + 2 * rn.length;

        final byte[] res = new byte[len];
        final ByteBuffer resB = ByteBuffer.wrap(res);

        resB.put(hexSize);
        resB.put(rn);
        resB.put(key);
        resB.put(n);
        resB.put(value);
        resB.put(rn);

        return res;
    }

    @Override
    protected void processWrite() throws Exception {
        super.processWrite();
        next();
    }

    private void next() throws IOException {
        while (iter.hasNext() && queueHead == null) {
            final Record record = iter.next();
            final byte[] chunk = makeChunk(record);
            write(chunk, 0, chunk.length);
        }
        if (iter.hasNext()) {
            return;
        }

        final byte[] empty = "0\r\n\r\n".getBytes(Charsets.UTF_8);
        write(empty, 0, empty.length);

        server.incRequestsProcessed();
        if ((handling = pipeline.pollFirst()) != null) {
            if (handling == FIN) {
                scheduleClose();
            } else {
                server.handleRequest(handling, this);
            }
        }
    }
}
