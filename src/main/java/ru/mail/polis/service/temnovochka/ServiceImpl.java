package ru.mail.polis.service.temnovochka;

import com.google.common.base.Charsets;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Socket;
import one.nio.server.AcceptorConfig;
import one.nio.server.RejectedSessionException;
import one.nio.server.Server;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.Record;
import ru.mail.polis.dao.ByteArrayUtils;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.service.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class ServiceImpl extends HttpServer implements Service {
    private static final Log log = LogFactory.getLog(Server.class);

    @NotNull
    private final DAO dao;

    public ServiceImpl(final int port, @NotNull final DAO dao) throws IOException {
        super(getConfig(port));
        this.dao = dao;
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

    private Response responseProcessEntity(final ByteBuffer id, final Request request) throws IOException {
        switch (request.getMethod()) {
            case Request.METHOD_GET:
                try {
                    final ByteBuffer resOfGet = dao.get(id);
                    final byte[] res = ByteArrayUtils.getArrayFromByteBuffer(resOfGet);
                    return Response.ok(res);
                } catch (NoSuchElementException e) {
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                }
            case Request.METHOD_PUT:
                dao.upsert(id, ByteBuffer.wrap(request.getBody()));
                return new Response(Response.CREATED, Response.EMPTY);
            case Request.METHOD_DELETE:
                dao.remove(id);
                return new Response(Response.ACCEPTED, Response.EMPTY);
            default:
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        }
    }

    /**
     * Serve requests for entity.
     *
     * @param id      - key for record
     * @param request - HTTP request
     * @param session - HTTP session
     * @throws IOException when something went wrong with socket
     */
    @Path("/v0/entity")
    public void entity(@Param("id") final String id,
                       @NotNull final Request request,
                       @NotNull final HttpSession session) throws IOException {
        if (id == null || id.isEmpty()) {
            session.sendError(Response.BAD_REQUEST, "No id");
            return;
        }
        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(Charsets.UTF_8));
        asyncExecute(() -> {
            try {
                final Response response = responseProcessEntity(key, request);
                session.sendResponse(response);
            } catch (IOException e) {
                try {
                    session.sendError(Response.INTERNAL_ERROR, e.getMessage());
                } catch (IOException ex) {
                    log.error("Something went wrong...", ex);
                }
            }
        });
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
