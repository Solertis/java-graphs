/*
 * The MIT License
 *
 * Copyright 2016 Thibault Debatty.
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

package info.debatty.java.graphs.build;

import info.debatty.java.graphs.Graph;
import info.debatty.java.graphs.Neighbor;
import info.debatty.java.graphs.NeighborList;
import info.debatty.java.graphs.Node;
import info.debatty.java.graphs.SimilarityInterface;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Thibault Debatty
 * @param <T>
 */
public class ThreadedBrute<T> extends GraphBuilder<T> {

    public static final int NODES_PER_BLOCK = 1000;

    @Override
    protected final Graph<T> _computeGraph(final List<Node<T>> nodes) {

        // Start all blocks
        int n = nodes.size();
        int cores = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(cores);
        ArrayList<Future<Graph<T>>> results = new ArrayList<Future<Graph<T>>>();

        for (int i = 0; i < n; i += NODES_PER_BLOCK) {
            for (int j = 0; j <= i; j += NODES_PER_BLOCK) {
                results.add(executor.submit(
                        new BruteBlock(nodes, k, similarity, i, j)));
            }
        }

        // Initialize all NeighborLists
        Graph<T> graph = new Graph<T>();
        for (Node node : nodes) {
            graph.put(node, new NeighborList(k));
        }

        // Aggregate all subgraphs
        for (Future<Graph<T>> future : results) {
            Graph<T> subgraph;
            try {
                subgraph = future.get();
                for (Map.Entry<Node<T>, NeighborList> entry : subgraph.entrySet()) {
                    graph.get(entry.getKey()).addAll(entry.getValue());
                }

            } catch (InterruptedException ex) {
                Logger.getLogger(ThreadedBrute.class.getName()).log(Level.SEVERE, null, ex);

            } catch (ExecutionException ex) {
                Logger.getLogger(ThreadedBrute.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        return graph;
    }
}

/**
 *
 * @author Thibault Debatty
 * @param <T>
 */
class BruteBlock<T> implements Callable<Graph<T>> {
    private final int i_start;
    private final List<Node<T>> nodes;
    private final SimilarityInterface<T> similarity;
    private final int k;
    private final int j_start;

    BruteBlock(
            final List<Node<T>> nodes,
            final int k,
            final SimilarityInterface<T> similarity,
            final int i_start,
            final int j_start) {

        this.nodes = nodes;
        this.k = k;
        this.similarity = similarity;
        this.i_start = i_start;
        this.j_start = j_start;
    }

    public Graph<T> call() throws Exception {

        int n = nodes.size();
        int i_end = Math.min(i_start + ThreadedBrute.NODES_PER_BLOCK, n);
        int j_end = Math.min(j_start + ThreadedBrute.NODES_PER_BLOCK, n);

        // Initialize neighborlists
        Graph<T> graph = new Graph<T>();
        for (int i = i_start; i < i_end; i++) {
            Node node = nodes.get(i);
            graph.put(node, new NeighborList(k));
        }

        for (int j = j_start; j < j_end; j++) {
            Node node = nodes.get(j);
            graph.put(node, new NeighborList(k));
        }

        for (int i = i_start; i < i_end; i++) {
            Node n1 = nodes.get(i);

            for (int j = j_start; j < j_end; j++) {

                if (i == j) {
                    break;
                }

                Node n2 = nodes.get(j);
                double sim = similarity.similarity((T) n1.value, (T) n2.value);

                graph.get(n1).add(new Neighbor(n2, sim));
                graph.get(n2).add(new Neighbor(n1, sim));
            }
        }

        return graph;
    }
}
