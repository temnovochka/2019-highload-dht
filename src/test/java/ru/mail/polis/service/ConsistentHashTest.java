package ru.mail.polis.service;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import ru.mail.polis.service.temnovochka.LoadRouter;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static ru.mail.polis.TestBase.randomKeyBuffer;

class ConsistentHashTest {
    @Test
    void equalNodes() {
        @NotNull final Set<Integer> ports = Set.of(8080, 8081, 8082, 8083, 8084, 8085, 8086, 8087, 8088, 8089);
        @NotNull final Set<String> topology = ports.stream()
                .map(it -> String.format("http://localhost:%d", it))
                .collect(Collectors.toSet());
        final LoadRouter loadRouter1 = new LoadRouter(topology, "http://localhost:" + 8080);
        final LoadRouter loadRouter2 = new LoadRouter(topology, "http://localhost:" + 8080);

        for (int i = 0; i < 10000; i++) {
            final ByteBuffer key = randomKeyBuffer();
            final var nodes1 = loadRouter1.selectNodeForKey(key, loadRouter1.getNumOfNodes());
            final var nodes2 = loadRouter2.selectNodeForKey(key, loadRouter2.getNumOfNodes());
            assertEquals(nodes1, nodes2);
        }
    }

    @Test
    void differentNodes() {
        @NotNull final Set<Integer> ports = Set.of(8080, 8081, 8082, 8083, 8084, 8085, 8086, 8087, 8088, 8089);
        @NotNull final Set<String> topology = ports.stream()
                .map(it -> String.format("http://localhost:%d", it))
                .collect(Collectors.toSet());
        final LoadRouter loadRouter = new LoadRouter(topology, "http://localhost:" + 8080);

        for (int i = 0; i < 10000; i++) {
            final ByteBuffer key = randomKeyBuffer();
            final var nodes = loadRouter.selectNodeForKey(key, loadRouter.getNumOfNodes());
            assertEquals(Set.of(nodes).size(), List.of(nodes).size());
        }
    }
}
