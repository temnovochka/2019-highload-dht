package ru.mail.polis.service.temnovochka;

import com.google.common.base.Charsets;
import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Socket;
import one.nio.pool.PoolException;
import one.nio.server.AcceptorConfig;
import one.nio.server.RejectedSessionException;
import one.nio.server.Server;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.Record;
import ru.mail.polis.dao.ByteArrayUtils;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.DAORecord;
import ru.mail.polis.service.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServiceImpl extends HttpServer implements Service {
    private static final Log log = LogFactory.getLog(Server.class);

    @NotNull
    private final DAO dao;
    @NotNull
    private final LoadRouter loadRouter;

    /**
     * Constructor for ServiceImpl.
     *
     * @param port       - server's port
     * @param dao        - data access object
     * @param loadRouter - divider of data between nodes
     * @throws IOException when something went wrong
     */
    public ServiceImpl(
            final int port,
            @NotNull final DAO dao,
            @NotNull final LoadRouter loadRouter) throws IOException {
        super(getConfig(port));
        this.dao = dao;
        this.loadRouter = loadRouter;
    }

    /**
     * Get config for server by port.
     *
     * @param port to accept HTTP connections
     * @return config
     */
    private static HttpServerConfig getConfig(final int port) {
        final AcceptorConfig acceptor = new AcceptorConfig();
        acceptor.port = port;
        final HttpServerConfig config = new HttpServerConfig();
        config.acceptors = new AcceptorConfig[]{acceptor};
        config.minWorkers = 1;
        config.maxWorkers = Runtime.getRuntime().availableProcessors();
        return config;
    }

    private Response responseProcessEntity(final ByteBuffer id,
                                           final Request request,
                                           long timestamp) throws IOException {
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

    private Request addHeaders(@NotNull final Request request, final long timestamp) {
        request.addHeader("X-WHO: SYSTEM");
        request.addHeader("X-TIMESTAMP: " + timestamp);
        return request;
    }

    private boolean isSystemRequest(@NotNull final Request request) {
        return "SYSTEM".equals(request.getHeader("X-WHO: ", "USER"));
    }

    private long getTimestamp(@NotNull final Request request) {
        final String timestamp = request.getHeader("X-TIMESTAMP: ");
        if (timestamp == null) {
            throw new IllegalStateException("Timestamp header expected");
        }
        return Long.parseLong(timestamp);
    }

    /**
     * Serve requests for entity.
     *
     * @param id       - key for record
     * @param replicas - replicas in format ack/from
     * @param request  - HTTP request
     * @param session  - HTTP session
     * @throws IOException when something went wrong with socket
     */
    @Path("/v0/entity")
    public void entity(@Param("id") final String id,
                       @Param("replicas") final String replicas,
                       @NotNull final Request request,
                       @NotNull final HttpSession session) throws IOException {
        if (id == null || id.isEmpty()) {
            session.sendError(Response.BAD_REQUEST, "No id");
            return;
        }
        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(Charsets.UTF_8));
        if (isSystemRequest(request)) {
            final long timestamp = getTimestamp(request);
            asyncExecute(() -> {
                try {
                    final Response response = responseProcessEntity(key, request, timestamp);
                    session.sendResponse(response);
                } catch (IOException e) {
                    try {
                        session.sendError(Response.INTERNAL_ERROR, e.getMessage());
                    } catch (IOException ex) {
                        log.error("Something went wrong...", ex);
                    }
                }
            });
            return;
        }
        int ack;
        int from;
        if (replicas == null || replicas.isEmpty()) {
            from = loadRouter.getNumOfNodes();
            ack = from / 2 + 1;
        } else {
            final Pattern regex = Pattern.compile("(\\d+)/(\\d+)");
            final Matcher match = regex.matcher(replicas);
            if (!match.find()) {
                session.sendError(Response.BAD_REQUEST, "Format of replicas is not correct");
                return;
            }
            ack = Integer.parseInt(match.group(1));
            from = Integer.parseInt(match.group(2));
        }
        if (from <= 0 || from > loadRouter.getNumOfNodes() || ack <= 0 || ack > from) {
            session.sendError(Response.BAD_REQUEST, "Format of replicas is not correct");
            return;
        }
        final List<LoadRouter.Node> nodes = loadRouter.selectNodeForKey(key, from);
        final long timestamp = System.currentTimeMillis();
        asyncExecute(() -> {
            try {
                addHeaders(request, timestamp);
                final List<Response> responses = new ArrayList<>();
                for (LoadRouter.Node node : nodes) {
                    if (node.isMe()) {
                        responses.add(responseProcessEntity(key, request, timestamp));
                    } else {
                        try {
                            responses.add(proxy(node.getClient(), request));
                        } catch (IOException e) {
                            log.error("Proxy went wrong", e);
                        }
                    }
                }
                switch (request.getMethod()) {
                    /*404, 200 - deleted / not deleted*/
                    case Request.METHOD_GET: {
                        final long successResponses = responses.stream()
                                .map(Response::getStatus)
                                .filter(it -> it.equals(200) || it.equals(404))
                                .count();
                        if (successResponses >= ack) {
                            DAORecord newestRecord = null;
                            for (Response response : responses) {
                                if (response.getStatus() != 200)
                                    continue;
                                final DAORecord record = DAORecord.fromBytes(response.getBody());
                                if (newestRecord == null || record.getTimestamp() > newestRecord.getTimestamp()) {
                                    newestRecord = record;
                                }
                            }
                            if (newestRecord == null || newestRecord.isDeleted()) {
                                session.sendResponse(new Response(Response.NOT_FOUND, Response.EMPTY));
                            } else {
                                final byte[] value = ByteArrayUtils.getArrayFromByteBuffer(newestRecord.getValue());
                                session.sendResponse(Response.ok(value));
                            }
                        } else {
                            session.sendResponse(new Response("504 Not Enough Replicas", Response.EMPTY));
                        }
                        break;
                    }
                    case Request.METHOD_PUT: {
                        final long successResponses = responses.stream()
                                .map(Response::getStatus)
                                .filter(it -> it.equals(201))
                                .count();
                        if (successResponses >= ack) {
                            session.sendResponse(new Response(Response.CREATED, Response.EMPTY));
                        } else {
                            session.sendResponse(new Response("504 Not Enough Replicas", Response.EMPTY));
                        }
                        break;
                    }
                    case Request.METHOD_DELETE: {
                        final long successResponses = responses.stream()
                                .map(Response::getStatus)
                                .filter(it -> it.equals(202))
                                .count();
                        if (successResponses >= ack) {
                            session.sendResponse(new Response(Response.ACCEPTED, Response.EMPTY));
                        } else {
                            session.sendResponse(new Response("504 Not Enough Replicas", Response.EMPTY));
                        }
                        break;
                    }
                    default:
                        session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
                        break;
                }
            } catch (IOException e) {
                try {
                    session.sendError(Response.INTERNAL_ERROR, e.getMessage());
                } catch (IOException ex) {
                    log.error("Something went wrong...", ex);
                }
            }
        });
    }

    private Response proxy(@NotNull final HttpClient client, @NotNull final Request request) throws IOException {
        try {
            return client.invoke(request);
        } catch (InterruptedException | PoolException | HttpException e) {
            throw new IOException("Proxy went wrong", e);
        }
    }

    @Override
    public HttpSession createSession(final Socket socket) throws RejectedSessionException {
        return new StorageSession(socket, this);
    }

    /**
     * Serve requests for entities from start to end.
     *
     * @param start   - key for record from which we start giving data
     * @param end     - key for record on which we stop giving data
     * @param request - HTTP request
     * @param session - HTTP session
     * @throws IOException when something went wrong with socket
     */
    @Path("/v0/entities")
    public void entities(@Param("start") final String start,
                         @Param("end") final String end,
                         @NotNull final Request request,
                         @NotNull final HttpSession session) throws IOException {
        if (start == null || start.isEmpty()) {
            session.sendError(Response.BAD_REQUEST, "No id");
            return;
        }
        if (request.getMethod() != Request.METHOD_GET) {
            session.sendError(Response.METHOD_NOT_ALLOWED, "Allowed only method GET");
            return;
        }
        asyncExecute(() -> {
            try {
                final ByteBuffer from = ByteBuffer.wrap(start.getBytes(Charsets.UTF_8));
                ByteBuffer to = null;
                if (end != null && !end.isEmpty()) {
                    to = ByteBuffer.wrap(end.getBytes(Charsets.UTF_8));
                }
                final Iterator<Record> iter = dao.range(from, to);

                final StorageSession storageSession = (StorageSession) session;
                storageSession.stream(iter);
            } catch (IOException e) {
                try {
                    session.sendError(Response.INTERNAL_ERROR, e.getMessage());
                } catch (IOException ex) {
                    log.error("Something went wrong...", ex);
                }
            }
        });
    }

    /**
     * Serve requests for status.
     *
     * @param request - HTTP request
     * @return status
     */
    @Path("/v0/status")
    public Response status(final Request request) {
        if (request.getMethod() == Request.METHOD_GET) {
            return Response.ok(Response.EMPTY);
        } else {
            return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        }
    }

    @Override
    public void handleDefault(final Request request, final HttpSession session) throws IOException {
        final Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }
}
