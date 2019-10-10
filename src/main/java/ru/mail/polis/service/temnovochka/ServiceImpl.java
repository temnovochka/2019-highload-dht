package ru.mail.polis.service.temnovochka;

import com.google.common.base.Charsets;
import one.nio.http.*;
import one.nio.server.AcceptorConfig;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.ByteArrayUtils;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.service.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.NoSuchElementException;

public class ServiceImpl extends HttpServer implements Service {

    private final DAO dao;

    public ServiceImpl(final int port, @NotNull final DAO dao) throws IOException {
        super(getConfig(port));
        this.dao = dao;
    }

    private static HttpServerConfig getConfig (final int port) {
        AcceptorConfig acceptor = new AcceptorConfig();
        acceptor.port = port;
        HttpServerConfig config = new HttpServerConfig();
        config.acceptors = new AcceptorConfig[]{acceptor};
        return config;

    }

    private Response ResponseProcessEntity(final ByteBuffer id, final Request request) throws IOException {
        switch (request.getMethod()) {
            case Request.METHOD_GET:
                try {
                    ByteBuffer resOfGet = dao.get(id);
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

    @Path("/v0/entity")
    public Response entity(@Param("id") final String id, final Request request) throws IOException {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        ByteBuffer key = ByteBuffer.wrap(id.getBytes(Charsets.UTF_8));
        try {
            return ResponseProcessEntity(key, request);
        } catch (IOException e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Path("/v0/status")
    public Response status(final Request request) {
        if (request.getMethod() == Request.METHOD_GET) {
            return Response.ok(Response.EMPTY);
        } else {
            return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }
}
