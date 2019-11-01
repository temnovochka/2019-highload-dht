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
import ru.mail.polis.dao.DAO;
import ru.mail.polis.service.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

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
                    final Response response = EntityWorker.responseProcessEntity(dao, key, request, timestamp);
                    session.sendResponse(response);
                } catch (IOException e) {
                    sendError(session, e.getMessage());
                }
            });
            return;
        }
        final EntityWorker.Replicas parsedReplicas = EntityWorker.parseReplicas(replicas, loadRouter.getNumOfNodes());
        if (parsedReplicas == null) {
            session.sendError(Response.BAD_REQUEST, "Format of replicas is not correct");
            return;
        }
        final List<LoadRouter.Node> nodes = loadRouter.selectNodeForKey(key, parsedReplicas.from);
        final long timestamp = System.currentTimeMillis();
        asyncExecute(() -> {
            try {
                addHeaders(request, timestamp);
                final List<Response> responses = new ArrayList<>();
                for (final LoadRouter.Node node : nodes) {
                    if (node.isMe()) {
                        responses.add(EntityWorker.responseProcessEntity(dao, key, request, timestamp));
                    } else {
                        proxy(node.getClient(), request).ifPresent(responses::add);
                    }
                }
                session.sendResponse(EntityWorker.makeResponse(request, parsedReplicas.ack, responses));
            } catch (IOException e) {
                sendError(session, e.getMessage());
            }
        });
    }

    private void sendError(@NotNull final HttpSession session, @NotNull final String message) {
        try {
            session.sendError(Response.INTERNAL_ERROR, message);
        } catch (IOException ex) {
            log.error("Something went wrong...", ex);
        }
    }

    private Optional<Response> proxy(@NotNull final HttpClient client, @NotNull final Request request) {
        try {
            return Optional.of(client.invoke(request));
        } catch (InterruptedException | PoolException | HttpException | IOException e) {
            log.error("Proxy went wrong", e);
            return Optional.empty();
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
                sendError(session, e.getMessage());
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
