package ru.mail.polis.service.temnovochka;

import com.google.common.collect.Range;
import com.google.common.collect.TreeRangeMap;
import com.google.common.hash.Hashing;
import one.nio.http.HttpClient;
import one.nio.net.ConnectionString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Class for divide data optimal in nodes.
 */
public class LoadRouter {

    private static final int PART_COUNT = 1 << 10;
    private static final int PART_SIZE = 1 << (Integer.SIZE - 10);
    private final TreeRangeMap<Integer, Node> nodeMap;

    /**
     * Class represents cluster node.
     */
    static class Node {

        @NotNull
        private final String endpoint;
        private final boolean isMe;
        @Nullable
        private final HttpClient client;

        Node(@NotNull final String endpoint, final boolean isMe, @Nullable final HttpClient client) {
            this.endpoint = endpoint;
            this.isMe = isMe;
            this.client = client;
        }

        public boolean isMe() {
            return this.isMe;
        }

        public HttpClient getClient() {
            if (this.isMe) {
                throw new IllegalStateException("Could not take client for current node");
            }
            return this.client;
        }
    }

    /**
     * Divide keys into nodes.
     *
     * @param topology        - cluster nodes
     * @param currentNodeName - node, where the request come
     */
    public LoadRouter(@NotNull final Set<String> topology, @NotNull final String currentNodeName) {
        final List<Node> nodes = topology.stream().sorted().map(name -> {
            final boolean isCurrent = name.equals(currentNodeName);
            final ConnectionString connectionString = new ConnectionString(name + "?timeout=" + 100);
            final HttpClient client = isCurrent ? null : new HttpClient(connectionString);
            return new Node(name, isCurrent, client);
        }).collect(Collectors.toList());

        final int nodesSize = nodes.size();
        this.nodeMap = TreeRangeMap.create();
        for (int i = 0; i < PART_COUNT - 1; i++) {
            final int counter = Integer.MIN_VALUE + PART_SIZE * i;
            final int next = counter + PART_SIZE;
            final Node node = nodes.get(i - nodesSize * (i / nodesSize));
            final Range<Integer> range = Range.closedOpen(counter, next);
            this.nodeMap.put(range, node);
        }
        final Node node = nodes.get(PART_COUNT - 1 - nodesSize * ((PART_COUNT - 1) / nodesSize));
        final Range<Integer> range = Range.closed(Integer.MAX_VALUE - PART_SIZE, Integer.MAX_VALUE);
        this.nodeMap.put(range, node);
    }

    /**
     * Give needed node for key.
     *
     * @param key of data
     * @return node, where data for asked key is
     */
    public Node selectNodeForKey(@NotNull final ByteBuffer key) {
        final int keyHash = Hashing.sha256().hashBytes(key.duplicate()).asInt();
        return this.nodeMap.get(keyHash);
    }
}