package ru.mail.polis.service.temnovochka;

import com.google.common.collect.ImmutableSet;
import one.nio.http.Request;
import one.nio.http.Response;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.ByteArrayUtils;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.DAORecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

public final class EntityWorker {

    private EntityWorker() {
    }

    /**
     * Make response for request from list of responses from nodes.
     *
     * @param request   - request from user
     * @param ack       - number of waited answers
     * @param responses - lias of responses from nodes
     * @return result response for user
     */
    public static Response makeResponse(@NotNull final Request request, final int ack, final List<Response> responses) {
        final Set<Integer> codes = ImmutableSet.of(200, 201, 202, 404);
        final long successResponses = responses.stream()
                .map(Response::getStatus)
                .filter(codes::contains)
                .count();
        if (successResponses < ack) {
            return new Response("504 Not Enough Replicas", Response.EMPTY);
        }
        switch (request.getMethod()) {
            case Request.METHOD_GET: {
                DAORecord newestRecord = null;
                for (final Response response : responses) {
                    if (response.getStatus() != 200) {
                        continue;
                    }
                    final DAORecord record = DAORecord.fromBytes(response.getBody());
                    if (newestRecord == null || record.getTimestamp() > newestRecord.getTimestamp()) {
                        newestRecord = record;
                    }
                }
                if (newestRecord == null || newestRecord.isDeleted()) {
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                } else {
                    final byte[] value = ByteArrayUtils.getArrayFromByteBuffer(newestRecord.getValue());
                    return Response.ok(value);
                }
            }
            case Request.METHOD_PUT: {
                return new Response(Response.CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE: {
                return new Response(Response.ACCEPTED, Response.EMPTY);
            }
            default:
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        }
    }

    /**
     * Get response for specified method.
     *
     * @param dao       - data access object
     * @param id        - id of record
     * @param request   - request from user
     * @param timestamp - timestamp in milliseconds
     * @return response for request
     * @throws IOException when something went wrong
     */
    public static Response responseProcessEntity(final DAO dao,
                                                 final ByteBuffer id,
                                                 final Request request,
                                                 final long timestamp) throws IOException {
        switch (request.getMethod()) {
            case Request.METHOD_GET:
                try {
                    final DAORecord resOfGet = dao.getRecord(id);
                    return Response.ok(resOfGet.toBytes());
                } catch (NoSuchElementException e) {
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                }
            case Request.METHOD_PUT:
                final ByteBuffer value = ByteBuffer.wrap(request.getBody());
                dao.upsertRecord(id, new DAORecord(value, timestamp, false));
                return new Response(Response.CREATED, Response.EMPTY);
            case Request.METHOD_DELETE:
                dao.upsertRecord(id, new DAORecord(ByteBuffer.allocate(0), timestamp, true));
                return new Response(Response.ACCEPTED, Response.EMPTY);
            default:
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        }
    }
}
