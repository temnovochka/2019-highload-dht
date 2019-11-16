package ru.mail.polis.service.temnovochka;

import one.nio.http.Response;
import org.jetbrains.annotations.NotNull;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;

public final class ResponseRepresentation {

    @NotNull
    private final ByteBuffer body;
    private final int status;

    private ResponseRepresentation(@NotNull final ByteBuffer body, final int status) {
        this.body = body;
        this.status = status;
    }

    /**
     * Create representation for http response.
     *
     * @param response - http response
     * @return representation
     */
    public static ResponseRepresentation create(final Response response) {
        return new ResponseRepresentation(ByteBuffer.wrap(response.getBody()), response.getStatus());
    }

    /**
     * Create representation for http response.
     *
     * @param response - http response
     * @return representation
     */
    public static ResponseRepresentation create(final HttpResponse<byte[]> response) {
        return new ResponseRepresentation(ByteBuffer.wrap(response.body()), response.statusCode());
    }

    @NotNull
    public ByteBuffer getBody() {
        return body;
    }

    public int getStatus() {
        return status;
    }
}
