package heapx.graph;

import heapx.model.HeapModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Shortest strong-reference path from any root to a target object, found by
 * BFS backwards over real reference edges (never dominator-tree edges).
 */
public final class PathFinder {

    public record Step(long fromId, String fromClass, String via, long toId, String toClass) {}

    /** @return steps ordered root-first, or null if the target is unreachable */
    public static List<Step> shortestPathFromRoot(HeapModel g, DominatorAnalysis da, int target) {
        if (target < 0 || target >= g.nodeCount() || da.unreachable[target]) return null;
        int n = g.nodeCount();
        int[] prevNode = new int[n];
        int[] prevEdge = new int[n];
        Arrays.fill(prevNode, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        prevNode[target] = target;
        queue.add(target);
        int foundRoot = -1;
        while (!queue.isEmpty()) {
            int v = queue.poll();
            if (g.root[v]) { foundRoot = v; break; }
            for (int e = g.inStart[v]; e < g.inStart[v + 1]; e++) {
                int u = g.inFrom[e];
                if (prevNode[u] == -1) {
                    prevNode[u] = v;
                    prevEdge[u] = e;
                    queue.add(u);
                }
            }
        }
        if (foundRoot < 0) return null;
        List<Step> steps = new ArrayList<>();
        int cur = foundRoot;
        while (cur != target) {
            int next = prevNode[cur];
            int edge = prevEdge[cur];
            steps.add(new Step(g.ids[cur], g.classNames[cur], g.inLabel[edge],
                    g.ids[next], g.classNames[next]));
            cur = next;
        }
        return steps;
    }
}
