/*
 * The MIT License
 *
 * Copyright 2015 Thibault Debatty.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package info.debatty.java.graphs;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.io.Writer;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Stack;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * k-nn graph, represented as a mapping node => neighborlist.
 *
 * @author Thibault Debatty
 * @param <T> The type of nodes value
 */
public class Graph<T> implements Serializable {

    /**
     * Number of edges per node.
     */
    public static final int DEFAULT_K = 10;

    /**
     * Fast search: speedup compared to exhaustive search.
     */
    public static final double DEFAULT_SEARCH_SPEEDUP = 4.0;

    /**
     * Fast search: expansion parameter.
     */
    public static final double DEFAULT_SEARCH_EXPANSION = 1.2;

    /**
     * Fast search: number of random jumps per node (to simulate small world
     * graph).
     */
    public static final int DEFAULT_SEARCH_RANDOM_JUMPS = 2;

    /**
     * Fast add or remove node: depth of search to update the graph.
     */
    public static final int DEFAULT_UPDATE_DEPTH = 3;

    private static final String NODE_SEQUENCE_KEY = "ONLINE_GRAPH_SEQUENCE";


    private final HashMap<Node<T>, NeighborList> map;
    private SimilarityInterface<T> similarity;
    private int k = DEFAULT_K;
    private int window_size = 0;
    private int current_sequence = 0;

    /**
     * Copy constructor.
     *
     * @param origin
     */
    public Graph(Graph<T> origin) {
        this.k = origin.k;
        this.window_size = origin.window_size;
        this.current_sequence = origin.current_sequence;
        this.similarity = origin.similarity;
        this.map = new HashMap<Node<T>, NeighborList>(origin.size());

        for (Node node : origin.getNodes()) {
            this.map.put(node, new NeighborList(origin.get(node)));
        }
    }

    /**
     * Initialize an empty graph, and set k (number of edges per node).
     * Default k is 10.
     * @param k
     */
    public Graph(final int k) {
        this.k = k;
        this.map = new HashMap<Node<T>, NeighborList>();
    }

    /**
     * Initialize an empty graph with k = 10.
     */
    public Graph() {
        this.map = new HashMap<Node<T>, NeighborList>();
    }

    /**
     * Get the similarity measure.
     * @return
     */
    public final SimilarityInterface<T> getSimilarity() {
        return similarity;
    }

    /**
     * Set the similarity measure used to build or search the graph.
     * @param similarity
     */
    public final void setSimilarity(final SimilarityInterface<T> similarity) {
        this.similarity = similarity;
    }

    /**
     * Get k (the number of edges per node).
     * @return
     */
    public final int getK() {
        return k;
    }

    /**
     * Set k (the number of edges per node).
     * The existing graph will not be modified.
     * @param k
     */
    public final void setK(final int k) {
        this.k = k;
    }

    /**
     * Get the neighborlist of this node.
     *
     * @param node
     * @return the neighborlist of this node
     */
    public final NeighborList get(final Node node) {
        return map.get(node);
    }

    /**
     * Get the first node in the graph.
     *
     * @return The first node in the graph
     * @throws NoSuchElementException if the graph is empty...
     */
    public final Node<T> first() throws NoSuchElementException {
        return this.getNodes().iterator().next();
    }

    /**
     * Remove from the graph all edges with a similarity lower than threshold.
     *
     * @param threshold
     */
    public final void prune(final double threshold) {
        for (NeighborList nl : map.values()) {

            // We cannot remove inside the loop
            // => do it in 2 steps:
            ArrayList<Neighbor> to_remove = new ArrayList<Neighbor>();
            for (Neighbor n : nl) {
                if (n.similarity < threshold) {
                    to_remove.add(n);
                }
            }

            nl.removeAll(to_remove);
        }
    }

    /**
     * Split the graph in connected components (usually you will first prune the
     * graph to remove "weak" edges).
     *
     * @return
     */
    public final ArrayList<Graph<T>> connectedComponents() {

        ArrayList<Graph<T>> subgraphs = new ArrayList<Graph<T>>();
        ArrayList<Node<T>> nodes_to_process =
                new ArrayList<Node<T>>(map.keySet());

        for (int i = 0; i < nodes_to_process.size(); i++) {
            Node n = nodes_to_process.get(i);
            if (n == null) {
                continue;
            }
            Graph<T> subgraph = new Graph<T>();
            subgraphs.add(subgraph);

            addAndFollow(subgraph, n, nodes_to_process);
        }

        return subgraphs;
    }

    private void addAndFollow(
            final Graph<T> subgraph,
            final Node<T> node,
            final ArrayList<Node<T>> nodes_to_process) {

        nodes_to_process.remove(node);

        NeighborList neighborlist = this.get(node);
        subgraph.put(node, neighborlist);

        if (neighborlist == null) {
            return;
        }

        for (Neighbor neighbor : this.get(node)) {
            if (!subgraph.containsKey(neighbor.node)) {
                addAndFollow(subgraph, neighbor.node, nodes_to_process);
            }
        }
    }

    /**
     * Computes the strongly connected sub-graphs (where every node is reachable
     * from every other node) using Tarjan's algorithm, which has computation
     * cost O(n).
     *
     * @return
     */
    public final ArrayList<Graph<T>> stronglyConnectedComponents() {

        Stack<NodeParent> explored_nodes = new Stack<NodeParent>();
        Index index = new Index();
        HashMap<Node, NodeProperty> bookkeeping =
                new HashMap<Node, NodeProperty>(map.size());

        ArrayList<Graph<T>> connected_components = new ArrayList<Graph<T>>();

        for (Node n : map.keySet()) {

            if (bookkeeping.containsKey(n)) {
                // This node was already processed...
                continue;
            }

            ArrayList<Node> connected_component =
                    this.strongConnect(n, explored_nodes, index, bookkeeping);

            if (connected_component == null) {
                continue;
            }

            // We found a connected component
            Graph<T> subgraph = new Graph<T>(connected_component.size());
            for (Node node : connected_component) {
                subgraph.put(node, this.get(node));
            }
            connected_components.add(subgraph);

        }

        return connected_components;
    }

    /**
     *
     * @param starting_point
     * @param explored_nodes
     * connected component.
     * @param index
     * @param bookkeeping
     * @return
     */
    private ArrayList<Node> strongConnect(
            final Node starting_point,
            final Stack<NodeParent> explored_nodes,
            final Index index,
            final HashMap<Node, NodeProperty> bookkeeping) {


        // explored_nodes stores the history of nodes explored but not yet
        // assigned to a strongly connected component

        // use a stack to perform depth first search (DFS) without using
        // recursion
        final Stack<NodeParent> nodes_to_process = new Stack<NodeParent>();
        nodes_to_process.push(new NodeParent(starting_point, null));


        while (!nodes_to_process.empty()) {
            NodeParent node_and_parent = nodes_to_process.pop();
            Node node = node_and_parent.node;

            bookkeeping.put(
                    node,
                    new NodeProperty(index.value(), index.value()));
            index.inc();
            explored_nodes.add(node_and_parent);

            // process neighbors of this node
            for (Neighbor neighbor : this.get(node)) {
                Node neighbor_node = neighbor.node;

                if (!this.containsKey(neighbor_node)
                        || this.get(neighbor_node) == null) {
                    // neighbor_node is actually part of another subgraph
                    // (this can happen during distributed processing)
                    // => skip
                    continue;
                }

                if (bookkeeping.containsKey(neighbor_node)) {
                    // this node was already processed
                    continue;
                }

                boolean skip = false;
                for (NodeParent node_in_queue : nodes_to_process) {
                    // Already in the queue for processing
                    if (node_in_queue.node.equals(neighbor_node)) {
                        skip = true;
                        break;
                    }
                }
                if (skip) {
                    continue;
                }

                // Perform depth first search...
                nodes_to_process.push(new NodeParent(neighbor_node, node));
            }
        }

        // Traverse the stack of explored nodes to update the lowlink value of
        // each node
        for (NodeParent node_and_parent : explored_nodes) {
            Node node = node_and_parent.parent;
            Node child = node_and_parent.node;

            if (node == null) {
                // this child is actually the starting point.
                continue;
            }

            bookkeeping.get(node).lowlink = Math.min(
                        bookkeeping.get(node).lowlink,
                        bookkeeping.get(child).lowlink);
        }

        for (NodeParent node_and_parent : explored_nodes) {
            Node node = node_and_parent.node;
            if (bookkeeping.get(node).lowlink == bookkeeping.get(node).index) {
                // node is the root of a strongly connected component
                // => fetch and return all nodes in this component
                ArrayList<Node> connected_component = new ArrayList<Node>();

                Node other_node;
                do {
                    other_node = explored_nodes.pop().node;
                    bookkeeping.get(other_node).onstack = false;
                    connected_component.add(other_node);
                } while (!starting_point.equals(other_node));

                return connected_component;
            }
        }

        return null;
    }

    /**
     * Store the node, and it's parent.
     */
    private static class NodeParent {
        private Node node;
        private Node parent;

        NodeParent(final Node node, final Node parent) {
            this.node = node;
            this.parent = parent;
        }
    }

    /**
     * Helper class to compute strongly connected components.
     */
    private static class Index {

        private int value;

        public int value() {
            return this.value;
        }

        public void inc() {
            this.value++;
        }
    }

    /**
     * Helper class to compute strongly connected components.
     */
    private static class NodeProperty {

        private int index;
        private int lowlink;
        private boolean onstack;

        NodeProperty(final int index, final int lowlink) {
            this.index = index;
            this.lowlink = lowlink;
            this.onstack = true;
        }
    };

    /**
     *
     * @param node
     * @param neighborlist
     * @return
     */
    public final NeighborList put(
            final Node<T> node, final NeighborList neighborlist) {
        return map.put(node, neighborlist);
    }

    /**
     *
     * @param node
     * @return
     */
    public final boolean containsKey(final Node node) {
        return map.containsKey(node);
    }

    /**
     *
     * @return
     */
    public final int size() {
        return map.size();
    }

    /**
     *
     * @return
     */
    public final Iterable<Map.Entry<Node<T>, NeighborList>> entrySet() {
        return map.entrySet();
    }

    /**
     *
     * @return
     */
    public final Iterable<Node<T>> getNodes() {
        return map.keySet();
    }

    /**
     * Recursively search neighbors of neighbors, up to a given depth.
     * @param starting_points
     * @param depth
     * @return
     */
    public final LinkedList<Node<T>> findNeighbors(
            final LinkedList<Node<T>> starting_points,
            final int depth) {
        LinkedList<Node<T>> neighbors = new LinkedList<Node<T>>();
        neighbors.addAll(starting_points);

        // I can NOT loop over candidates as I will add items to it inside the
        // loop!
        for (Node<T> start_node : starting_points) {

            // As depth will be small, I can use recursion here...
            findNeighbors(neighbors, start_node, depth);
        }


        return neighbors;
    }

    private void findNeighbors(
            final LinkedList<Node<T>> candidates,
            final Node<T> node,
            final int current_depth) {

        // With the distributed online algorithm, the nl might be null
        // because it is located on another partition
        NeighborList nl = get(node);
        if (nl == null) {
            return;
        }

        for (Neighbor n : nl) {
            if (!candidates.contains(n.node)) {
                candidates.add(n.node);

                if (current_depth > 0) {
                    // don't use current_depth++ here as we will reuse it in
                    // the for loop !
                    findNeighbors(candidates, n.node, current_depth - 1);
                }
            }
        }

    }

    /**
     * Get the underlying hash map that stores the nodes and associated
     * neighborlists.
     * @return
     */
    public final HashMap<Node<T>, NeighborList> getHashMap() {
        return map;
    }

    /**
     * Multi-thread exhaustive search.
     * @param query
     * @param k
     * @return
     * @throws InterruptedException if thread is interrupted
     * @throws ExecutionException if thread cannot complete
     */
    public final NeighborList searchExhaustive(final T query, final int k)
            throws InterruptedException, ExecutionException {

        // Read all nodes
        ArrayList<Node<T>> nodes = new ArrayList<Node<T>>();
        for (Node<T> node : getNodes()) {
            nodes.add(node);
        }

        int procs = Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(procs);
        List<Future<NeighborList>> results = new ArrayList();

        for (int i = 0; i < procs; i++) {
            int start = nodes.size() / procs * i;
            int stop = Math.min(nodes.size() / procs * (i + 1), nodes.size());

            results.add(pool.submit(new SearchTask(nodes, query, start, stop)));
        }

        // Reduce
        NeighborList neighbors = new NeighborList(k);
        for (Future<NeighborList> future : results) {
            neighbors.addAll(future.get());
        }
        pool.shutdown();
        return neighbors;
    }

    /**
     * Class used for multi-thread search.
     */
    private class SearchTask implements Callable<NeighborList> {

        private final ArrayList<Node<T>> nodes;
        private final T query;
        private final int start;
        private final int stop;

        SearchTask(
                final ArrayList<Node<T>> nodes,
                final T query,
                final int start,
                final int stop) {

            this.nodes = nodes;
            this.query = query;
            this.start = start;
            this.stop = stop;
        }

        public NeighborList call() throws Exception {
            NeighborList nl = new NeighborList(k);
            for (int i = start; i < stop; i++) {
                Node<T> other = nodes.get(i);
                nl.add(new Neighbor(
                        other,
                        similarity.similarity(query, other.value)));
            }
            return nl;

        }
    }

    /**
     * Approximate fast graph based search, as published in "Fast Online k-nn
     * Graph Building" by Debatty et al.
     * Default speedup is 4.
     *
     * @see <a href="http://arxiv.org/abs/1602.06819">Fast Online k-nn Graph
     * Building</a>
     * @param query
     * @param k search K neighbors
     * @return
     */
    public final NeighborList fastSearch(final T query, final int k) {
        return fastSearch(query, k, DEFAULT_SEARCH_SPEEDUP);
    }

    /**
     * Approximate fast graph based search, as published in "Fast Online k-nn
     * Graph Building" by Debatty et al.
     *
     * @see <a href="http://arxiv.org/abs/1602.06819">Fast Online k-nn Graph
     * Building</a>
     * @param query
     * @param k search k neighbors
     * @param speedup speedup for searching (> 1, default 4)
     * @return
     */
    public final NeighborList fastSearch(
            final T query, final int k, final double speedup) {

        return this.fastSearch(
                query,
                k,
                speedup,
                DEFAULT_SEARCH_RANDOM_JUMPS,
                DEFAULT_SEARCH_EXPANSION);
    }

    /**
     * Approximate fast graph based search, as published in "Fast Online k-nn
     * Graph Building" by Debatty et al.
     *
     * @see <a href="http://arxiv.org/abs/1602.06819">Fast Online k-nn Graph
     * Building</a>
     * @param query
     * @param k
     * @param speedup
     * @param long_jumps
     * @param expansion
     * @return
     */
    public final NeighborList fastSearch(
            final T query,
            final int k,
            final double speedup,
            final int long_jumps,
            final double expansion) {

        return this.fastSearch(
                query,
                k,
                speedup,
                DEFAULT_SEARCH_RANDOM_JUMPS,
                DEFAULT_SEARCH_EXPANSION,
                new StatisticsContainer());
    }

    /**
     * Approximate fast graph based search, as published in "Fast Online k-nn
     * Graph Building" by Debatty et al.
     *
     * @see <a href="http://arxiv.org/abs/1602.06819">Fast Online k-nn Graph
     * Building</a>
     * @param query query point
     * @param k number of neighbors to find (the K from K-nn search)
     * @param speedup (default: 4.0)
     * @param long_jumps (default: 2)
     * @param expansion (default: 1.2)
     * @param stats
     *
     * @return
     */
    public final NeighborList fastSearch(
            final T query,
            final int k,
            final double speedup,
            final int long_jumps,
            final double expansion,
            final StatisticsContainer stats) {

        if (speedup <= 1.0) {
            throw new InvalidParameterException("Speedup should be > 1.0");
        }

        int max_similarities = (int) (map.size() / speedup);

        // Looking for more nodes than this graph contains...
        // Or fall back to exhaustive search
        if (k >= map.size()
                || max_similarities >= map.size()) {

            NeighborList nl = new NeighborList(k);
            for (Node<T> node : map.keySet()) {
                nl.add(
                        new Neighbor(
                                node,
                                similarity.similarity(
                                        query,
                                        node.value)));
                stats.incSearchSimilarities();
            }
            return nl;
        }

        // Node => Similarity with query node
        HashMap<Node<T>, Double> visited_nodes = new HashMap<Node<T>, Double>();
        double global_highest_similarity = 0;
        ArrayList<Node<T>> nodes = new ArrayList<Node<T>>(map.keySet());
        Random rand = new Random();

        while (true) { // Restart...

            if (stats.getSearchSimilarities() >= max_similarities) {
                break;
            }

            stats.incSearchRestarts();

            // Select a random node from the graph
            Node<T> current_node = nodes.get(rand.nextInt(nodes.size()));

            // Already been here => restart
            if (visited_nodes.containsKey(current_node)) {
                continue;
            }

            // starting point too far (similarity too small) => restart!
            double restart_similarity = similarity.similarity(
                    query,
                    current_node.value);
            stats.incSearchSimilarities();
            if (restart_similarity < global_highest_similarity / expansion) {
                continue;
            }

            while (stats.getSearchSimilarities() < max_similarities) {

                NeighborList nl = this.get(current_node);

                // Node has no neighbor (cross partition edge) => restart!
                if (nl == null) {
                    stats.incSearchCrossPartitionRestarts();
                    break;
                }

                Node<T> node_higher_similarity = null;
                Node<T> other_node;

                for (int i = 0; i < long_jumps; i++) {
                    // Check a random node (to simulate long jumps)
                    other_node = nodes.get(rand.nextInt(nodes.size()));

                    // Already been here => skip
                    if (visited_nodes.containsKey(other_node)) {
                        continue;
                    }

                    // Compute similarity to query
                    double sim = similarity.similarity(
                            query,
                            other_node.value);
                    stats.incSearchSimilarities();
                    visited_nodes.put(other_node, sim);

                    // If this node provides an improved similarity, keep it
                    if (sim > restart_similarity) {
                        node_higher_similarity = other_node;
                        restart_similarity = sim;
                    }

                }

                // Check the neighbors of current_node and try to find a node
                // with higher similarity
                Iterator<Neighbor> y_nl_iterator = nl.iterator();
                while (y_nl_iterator.hasNext()) {

                    other_node = y_nl_iterator.next().node;

                    if (visited_nodes.containsKey(other_node)) {
                        continue;
                    }

                    // Compute similarity to query
                    double sim = similarity.similarity(
                            query,
                            other_node.value);
                    stats.incSearchSimilarities();
                    visited_nodes.put(other_node, sim);

                    // If this node provides an improved similarity, keep it
                    if (sim > restart_similarity) {
                        node_higher_similarity = other_node;
                        restart_similarity = sim;

                        // early break...
                        break;
                    }
                }

                // No node provides higher similarity
                // => we reached the end of this track...
                // => restart!
                if (node_higher_similarity == null) {

                    if (restart_similarity > global_highest_similarity) {
                        global_highest_similarity = restart_similarity;
                    }
                    break;
                }

                current_node = node_higher_similarity;
            }
        }

        NeighborList neighbor_list = new NeighborList(k);
        for (Map.Entry<Node<T>, Double> entry : visited_nodes.entrySet()) {
            neighbor_list.add(new Neighbor(entry.getKey(), entry.getValue()));
        }
        return neighbor_list;
    }

    /**
     * Writes the graph as a GEXF file (to be used in Gephi, for example).
     *
     * @param filename
     * @throws FileNotFoundException if filename is invalid
     * @throws IOException if cannot write to file
     */
    public final void writeGEXF(final String filename)
            throws FileNotFoundException, IOException {
        Writer out = new OutputStreamWriter(new FileOutputStream(filename));
        out.write(GEXF_HEADER);

        // Write nodes
        out.write("<nodes>\n");
        for (Node node : map.keySet()) {
            out.write("<node id=\"" + node.id
                    + "\" label=\"" + node.id + "\" />\n");
        }
        out.write("</nodes>\n");

        // Write edges
        out.write("<edges>\n");
        int i = 0;
        for (Node source : map.keySet()) {
            for (Neighbor target : this.get(source)) {
                out.write("<edge id=\"" + i + "\" source=\"" + source.id + "\" "
                        + "target=\"" + target.node.id + "\" "
                        + "weight=\"" + target.similarity + "\" />\n");
                i++;
            }
        }

        out.write("</edges>");

        // End the file
        out.write("</graph>\n"
                + "</gexf>");
        out.close();
    }

    private static final String GEXF_HEADER
            = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<gexf xmlns=\"http://www.gexf.net/1.2draft\" version=\"1.2\">\n"
            + "<meta>\n"
            + "<creator>info.debatty.java.graphs.Graph</creator>\n"
            + "<description></description>\n"
            + "</meta>\n"
            + "<graph mode=\"static\" defaultedgetype=\"directed\">\n";



    /**
     * Set the size of the window (number of nodes to keep in the graph).
     * Default = 0 = unlimited size
     * @param window_size
     */
    public final void setWindowSize(final int window_size) {
        this.window_size = window_size;
    }

    /**
     * Add a node to the online graph using exhaustive search approach.
     * Adding a node requires to compute the similarity between the new node
     * and every other node in the graph...
     * @param new_node
     * @return
     */
    public final int add(final Node<T> new_node) {
        if (containsKey(new_node)) {
            throw new IllegalArgumentException(
                    "This graph already contains a node with the same id!");
        }

        // Give a sequence number to the node (if it has to be removed later)
        new_node.setAttribute(NODE_SEQUENCE_KEY, current_sequence);
        current_sequence++;

        NeighborList nl = new NeighborList(k);

        for (Node<T> other_node : getNodes()) {
            double sim = similarity.similarity(
                    new_node.value, other_node.value);
            nl.add(new Neighbor(other_node, sim));
            get(other_node).add(new Neighbor(new_node, sim));
        }

        this.put(new_node, nl);
        return (size() - 1);

    }

    /**
     * Add a node to the online graph, using approximate online graph building
     * algorithm presented in "Fast Online k-nn Graph Building" by Debatty
     * et al. Default speedup is 4 compared to exhaustive search.
     *
     * @param node
     */
    public final void fastAdd(final Node<T> node) {
        fastAdd(node, DEFAULT_SEARCH_SPEEDUP);
    }

    /**
     * Add a node to the online graph, using approximate online graph building
     * algorithm presented in "Fast Online k-nn Graph Building" by Debatty
     * et al. Uses default number of long jumps (2) and default expansion (1.2).
     *
     * @param node
     * @param speedup
     */
    public final void fastAdd(final Node<T> node, final double speedup) {
        fastAdd(
                node,
                speedup,
                DEFAULT_SEARCH_RANDOM_JUMPS,
                DEFAULT_SEARCH_EXPANSION);
    }

    /**
     * Add a node to the online graph, using approximate online graph building
     * algorithm presented in "Fast Online k-nn Graph Building" by Debatty
     * et al.
     *
     * @param new_node
     * @param speedup compared to exhaustive search
     * @param long_jumps
     * @param expansion
     */
    public final void fastAdd(
            final Node<T> new_node,
            final double speedup,
            final int long_jumps,
            final double expansion) {

        fastAdd(
                new_node,
                speedup,
                DEFAULT_SEARCH_RANDOM_JUMPS,
                DEFAULT_SEARCH_EXPANSION,
                DEFAULT_UPDATE_DEPTH,
                new StatisticsContainer());
    }

    /**
     * Add a node to the online graph, using approximate online graph building
     * algorithm presented in "Fast Online k-nn Graph Building" by Debatty
     * et al.
     *
     * @param new_node
     * @param speedup compared to exhaustive search
     * @param long_jumps
     * @param expansion
     * @param update_depth
     * @param stats
     */
    public final void fastAdd(
            final Node<T> new_node,
            final double speedup,
            final int long_jumps,
            final double expansion,
            final int update_depth,
            final StatisticsContainer stats) {

        if (containsKey(new_node)) {
            throw new IllegalArgumentException(
                    "This graph already contains a node with the same id!");
        }

        // 1. Give a sequence number to the node (if it has to be removed later)
        new_node.setAttribute(NODE_SEQUENCE_KEY, current_sequence);
        current_sequence++;

        // 2. If needed, remove a node
        // We remove before adding the new node, as this reduces computation
        if (window_size != 0) {
            int node_to_delete = current_sequence - window_size - 1;
            for (Node<T> node : getNodes()) {
                if (node.getAttribute(NODE_SEQUENCE_KEY)
                        .equals(node_to_delete)) {
                     fastRemove(node, update_depth, stats);
                     break;
                }
            }
        }

        // 3. Search the neighbors of the new node
        NeighborList neighborlist = fastSearch(
                new_node.value, k, speedup, long_jumps, expansion, stats);
        put(new_node, neighborlist);

        // 4. Update existing edges
        // Nodes to analyze at this iteration
        LinkedList<Node<T>> analyze = new LinkedList<Node<T>>();

        // Nodes to analyze at next iteration
        LinkedList<Node<T>> next_analyze = new LinkedList<Node<T>>();

        // List of already analyzed nodes
        HashMap<Node<T>, Boolean> visited = new HashMap<Node<T>, Boolean>();

        // Fill the list of nodes to analyze
        for (Neighbor neighbor : get(new_node)) {
            analyze.add(neighbor.node);
        }

        for (int d = 0; d < update_depth; d++) {
            while (!analyze.isEmpty()) {
                Node<T> other = analyze.pop();
                NeighborList other_neighborlist = get(other);

                // Add neighbors to the list of nodes to analyze at
                // next iteration
                for (Neighbor other_neighbor : other_neighborlist) {
                    if (!visited.containsKey(other_neighbor.node)) {
                        next_analyze.add(other_neighbor.node);
                    }
                }

                // Try to add the new node (if sufficiently similar)
                stats.incAddSimilarities();
                other_neighborlist.add(new Neighbor(
                        new_node,
                        similarity.similarity(
                                new_node.value,
                                other.value)));

                visited.put(other, Boolean.TRUE);
            }

            analyze = next_analyze;
            next_analyze = new LinkedList<Node<T>>();
        }
    }

    /**
     * Remove a node from the graph (and update the graph) using fast
     * approximate algorithm.
     * @param node_to_remove
     */
    public final void fastRemove(final Node<T> node_to_remove) {
        fastRemove(node_to_remove, DEFAULT_UPDATE_DEPTH, new StatisticsContainer());
    }

    /**
     * Remove a node from the graph (and update the graph) using fast
     * approximate algorithm.
     * @param node_to_remove
     * @param update_depth
     * @param stats
     */
    public final void fastRemove(
            final Node<T> node_to_remove,
            final int update_depth,
            final StatisticsContainer stats) {

        // Build the list of nodes to update
        LinkedList<Node<T>> nodes_to_update = new LinkedList<Node<T>>();

        for (Node<T> node : getNodes()) {
            NeighborList nl = get(node);
            if (nl.containsNode(node_to_remove)) {
                nodes_to_update.add(node);
                nl.removeNode(node_to_remove);
            }
        }

        // Build the list of candidates
        LinkedList<Node<T>> initial_candidates = new LinkedList<Node<T>>();
        initial_candidates.add(node_to_remove);
        initial_candidates.addAll(nodes_to_update);

        LinkedList<Node<T>> candidates = findNeighbors(
                initial_candidates, update_depth);
        while (candidates.contains(node_to_remove)) {
            candidates.remove(node_to_remove);
        }

        // Update the nodes_to_update
        for (Node<T> node_to_update : nodes_to_update) {
            NeighborList nl_to_update = get(node_to_update);
            for (Node<T> candidate : candidates) {
                if (candidate.equals(node_to_update)) {
                    continue;
                }

                stats.incRemoveSimilarities();
                double sim = similarity.similarity(
                        node_to_update.value,
                        candidate.value);

                nl_to_update.add(new Neighbor(candidate, sim));
            }
        }

        // Remove node_to_remove
        map.remove(node_to_remove);
    }

    @Override
    public final String toString() {
        return map.toString();
    }
}
