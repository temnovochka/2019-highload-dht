package ru.mail.polis.service.temnovochka;

import com.google.common.collect.ImmutableSet;
import one.nio.http.Request;
import one.nio.http.Response;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.ByteArrayUtils;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.DAORecord;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EntityWorker {

    public static final class Replicas {
        final int ack;
        final int from;

        Replicas(final int ack, final int from) {
            this.ack = ack;
            this.from = from;
        }
    }

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
    public static Response makeResponse(@NotNull final Request request,
                                        final int ack,
                                        final List<ResponseRepresentation> responses) {
        final Set<Integer> codes = ImmutableSet.of(200, 201, 202, 404);
        final long successResponses = responses.stream()
                .map(ResponseRepresentation::getStatus)
                .filter(codes::contains)
                .count();
        if (successResponses < ack) {
            return new Response("504 Not Enough Replicas", Response.EMPTY);
        }
        switch (request.getMethod()) {
            case Request.METHOD_GET: {
                DAORecord newestRecord = null;
                for (final ResponseRepresentation response : responses) {
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

    /**
     * Parse replicas from request.
     *
     * @param replicas      - given replicas from request
     * @param numberOfNodes - number of nodes in service
     * @return parsed replicas
     */
    public static Replicas parseReplicas(final String replicas, final int numberOfNodes) {
        if (replicas == null || replicas.isEmpty()) {
            final int ack = numberOfNodes / 2 + 1;
            return new Replicas(ack, numberOfNodes);
        }
        final Pattern regex = Pattern.compile("(\\d+)/(\\d+)");
        final Matcher match = regex.matcher(replicas);
        if (!match.find()) {
            return null;
        }
        final int ack = Integer.parseInt(match.group(1));
        final int from = Integer.parseInt(match.group(2));
        if (from > numberOfNodes || ack <= 0 || ack > from) {
            return null;
        }
        return new Replicas(ack, from);
    }

    /**
     * Proxy entity request to a given node.
     *
     * @param id        - id of entity
     * @param request   - original http request
     * @param timestamp - timestamp of request
     * @param node      - given node
     * @param client    - http client
     * @return response future
     */
    public static CompletableFuture<ResponseRepresentation> proxy(final String id,
                                                                  @NotNull final Request request,
                                                                  final long timestamp,
                                                                  final LoadRouter.Node node,
                                                                  final HttpClient client) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(node.getName() + "/v0/entity?id=" + id))
                .timeout(Duration.ofMillis(100))
                .setHeader("X-WHO", "SYSTEM")
                .setHeader("X-TIMESTAMP", Long.toString(timestamp));
        if (request.getMethod() == Request.METHOD_GET) {
            requestBuilder = requestBuilder.GET();
        } else if (request.getMethod() == Request.METHOD_PUT) {
            requestBuilder = requestBuilder.PUT(HttpRequest.BodyPublishers.ofByteArray(request.getBody()));
        } else if (request.getMethod() == Request.METHOD_DELETE) {
            requestBuilder = requestBuilder.DELETE();
        } else {
            throw new IllegalStateException();
        }
        final HttpRequest httpRequest = requestBuilder.build();
        return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(ResponseRepresentation::create);
    }

    /**
     * Process entity request on a local node.
     *
     * @param request   - original http request
     * @param key       - key of entity
     * @param timestamp - timestamp of request
     * @param dao       - data access object
     * @return response future
     */
    @NotNull
    public static CompletableFuture<ResponseRepresentation> processRequestLocally(@NotNull final Request request,
                                                                                  final ByteBuffer key,
                                                                                  final long timestamp,
                                                                                  final DAO dao) {
        final CompletableFuture<ResponseRepresentation> response = new CompletableFuture<>();
        try {
            final Response r = responseProcessEntity(dao, key, request, timestamp);
            final ResponseRepresentation representation = ResponseRepresentation.create(r);
            response.complete(representation);
        } catch (IOException e) {
            response.completeExceptionally(e);
        }
        return response;
    }
}
