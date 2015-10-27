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

package info.debatty.java.graphs.examples;

import info.debatty.java.graphs.*;
import info.debatty.java.graphs.build.Brute;
import info.debatty.java.graphs.build.GraphBuilder;
import info.debatty.java.stringsimilarity.JaroWinkler;
import java.util.ArrayList;
import java.util.Random;


public class SearchExample {

    public static void main(String[] args) {
        int tests = 100;
        
        // Number of neighbors to search
        int k = 1;
        
        // Read the file
        ArrayList<Node<String>> nodes = GraphBuilder.readFile("/home/tibo/Downloads/726-unique-spams");
        
        // Leave some random nodes out for the search queries
        Random rand = new Random();
        ArrayList<Node<String>> queries = new ArrayList<Node<String>>(tests);
        for (int i = 0; i < tests; i++) {
            queries.add(nodes.remove(rand.nextInt(nodes.size())));
        }
        
        // Define the similarity to use
        SimilarityInterface<String> similarity = new SimilarityInterface<String>() {
            
            public double similarity(String value1, String value2) {
                JaroWinkler jw = new JaroWinkler();
                return jw.similarity(value1, value2);
            }
        };
        
        // Compute the graph
        Brute<String> builder = new Brute();
        builder.setSimilarity(similarity);
        builder.setK(10);
        Graph<String> graph = builder.computeGraph(nodes);
        
        // Perform some research...
        int correct = 0;
        
        for (Node<String> query : queries) {
            
            // Perform GNNS
            System.out.println("Query: " + query);
            NeighborList resultset_gnss = graph.search(query, k, 6, 10, similarity);
            System.out.println(resultset_gnss);
            
            // Perform linear search
            NeighborList resultset_linear = new NeighborList(k);
            for (Node<String> candidate : nodes) {
                resultset_linear.add(
                        new Neighbor(
                                candidate,
                                similarity.similarity(
                                        query.value,
                                        candidate.value)));
            }
            System.out.println(resultset_linear);
            
            correct += resultset_gnss.CountCommons(resultset_linear);
        }
        
        System.out.println("Correct: " + correct);
    }
}