package ru.mail.polis.service.temnovochka;

import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import com.google.common.collect.Range;
import com.google.common.collect.TreeRangeMap;
import com.google.common.hash.Hashing;
import one.nio.http.HttpClient;
import one.nio.net.ConnectionString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Class for divide data optimal in nodes.
 */
public class LoadRouter {

    private static final int PART_COUNT = 1 << 10;
    private static final int PART_SIZE = 1 << (Integer.SIZE - 10);
    private final TreeRangeMap<Integer, Node> nodeMap;
    private final List<Node> nodes;

    /**
     * Class represents cluster node.
     */
    static class Node {

        private final boolean isMe;
        @Nullable
        private final HttpClient client;
        @NotNull
        private final String name;

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof Node)) return false;
            final Node node = (Node) o;
            return isMe == node.isMe && Objects.equals(client, node.client) && name.equals(node.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(isMe, client, name);
        }

        Node(final boolean isMe, @Nullable final HttpClient client, @NotNull final String name) {
            this.isMe = isMe;
            this.client = client;
            this.name = name;
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
        this.nodes = topology.stream().sorted().map(name -> {
            final boolean isCurrent = name.equals(currentNodeName);
            final ConnectionString connectionString = new ConnectionString(name + "?timeout=" + 100);
            final HttpClient client = isCurrent ? null : new HttpClient(connectionString);
            return new Node(isCurrent, client, name);
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
     * @param key        of data
     * @param numOfNodes number of needed nodes to return
     * @return list of nodes, where data for asked key is
     */
    public List<Node> selectNodeForKey(@NotNull final ByteBuffer key, final int numOfNodes) {
        final int keyHash = Hashing.sha256().hashBytes(key.duplicate()).asInt();
        final Node node = this.nodeMap.get(keyHash);
        final List<Node> resultNodes = new ArrayList<>(numOfNodes);
        final PeekingIterator<Node> iterator = Iterators.peekingIterator(Iterators.cycle(nodes));
        while (!iterator.peek().equals(node)) {
            iterator.next();
        }
        for (int i = 0; i < numOfNodes; i++) {
            resultNodes.add(iterator.next());
        }
        return resultNodes;
    }

    /**
     * Getting number of nodes in service.
     *
     * @return number of nodes
     */
    public int getNumOfNodes() {
        return this.nodes.size();
    }
}
