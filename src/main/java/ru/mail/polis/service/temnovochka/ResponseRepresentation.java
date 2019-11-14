package ru.mail.polis.service.temnovochka;

import one.nio.http.Response;
import org.jetbrains.annotations.NotNull;

import java.net.http.HttpResponse;

public final class ResponseRepresentation {

    @NotNull
    private final byte[] body;
    private final int status;

    private ResponseRepresentation(@NotNull final byte[] body, final int status) {
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
        return new ResponseRepresentation(response.getBody(), response.getStatus());
    }

    /**
     * Create representation for http response.
     *
     * @param response - http response
     * @return representation
     */
    public static ResponseRepresentation create(final HttpResponse<byte[]> response) {
        return new ResponseRepresentation(response.body(), response.statusCode());
    }

    @NotNull
    public byte[] getBody() {
        return body;
    }

    public int getStatus() {
        return status;
    }
}
