package heapx.graph;

import heapx.model.HeapModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Minimum-cost strong-reference cut plan: picks the cheapest subset of the
 * user-supplied cuttable references so that every target object becomes
 * unreachable from all GC roots. Solved exactly as a single minimum s-t cut
 * (super-source -> every root, every target -> super-sink; uncuttable edges
 * and root/target arcs have infinite capacity), so shared paths, multiple
 * roots, cycles and distinct fields between the same objects are optimized
 * jointly rather than per target. Read-only: the heap model is never mutated.
 */
public final class CutPlanner {

    /** One validated cuttable reference; edgeIndex indexes HeapModel.outTo. */
    public record Candidate(int from, int to, String label, long cost, int edgeIndex) {}

    public record Result(boolean feasible, long totalCost, List<Candidate> cuts,
                         int evidenceTarget, List<PathFinder.Step> evidence) {}

    public static Result plan(HeapModel g, int[] targets, List<Candidate> candidates) {
        int n = g.nodeCount();
        long[] cutCost = new long[g.edgeCount()]; // 0 = not cuttable
        long sum = 0;
        for (Candidate c : candidates) {
            cutCost[c.edgeIndex()] = c.cost();
            sum += c.cost();
        }
        final long inf = sum + 1; // larger than any payable cut
        int source = n, sink = n + 1;
        Dinic dinic = new Dinic(n + 2);
        for (int u = 0; u < n; u++) {
            for (int e = g.outStart[u]; e < g.outStart[u + 1]; e++) {
                long cap = cutCost[e] > 0 ? cutCost[e] : inf;
                dinic.addArc(u, g.outTo[e], cap);
            }
            if (g.root[u]) dinic.addArc(source, u, inf);
        }
        boolean[] isTarget = new boolean[n];
        for (int t : targets) {
            isTarget[t] = true;
            dinic.addArc(t, sink, inf);
        }
        long flow = dinic.maxFlow(source, sink);
        if (flow >= inf) {
            int hit = evidenceTarget(g, isTarget, cutCost);
            return new Result(false, 0, List.of(), hit, evidencePath(g, hit, cutCost));
        }
        boolean[] side = dinic.reachableFrom(source);
        List<Candidate> cuts = new ArrayList<>();
        long total = 0;
        for (Candidate c : candidates) {
            if (side[c.from()] && !side[c.to()]) {
                cuts.add(c);
                total += c.cost();
            }
        }
        return new Result(true, total, cuts, -1, List.of());
    }

    /** Objects reachable from all roots; removedEdges may be null (original graph). */
    public static boolean[] reachableFrom(HeapModel g, boolean[] removedEdges) {
        int n = g.nodeCount();
        boolean[] seen = new boolean[n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (g.root[i]) { seen[i] = true; queue.add(i); }
        }
        while (!queue.isEmpty()) {
            int v = queue.poll();
            for (int e = g.outStart[v]; e < g.outStart[v + 1]; e++) {
                if (removedEdges != null && removedEdges[e]) continue;
                int w = g.outTo[e];
                if (!seen[w]) { seen[w] = true; queue.add(w); }
            }
        }
        return seen;
    }

    /** First target still reachable using uncuttable edges only, or -1. */
    private static int evidenceTarget(HeapModel g, boolean[] isTarget, long[] cutCost) {
        int n = g.nodeCount();
        boolean[] seen = new boolean[n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (g.root[i]) { seen[i] = true; queue.add(i); }
        }
        while (!queue.isEmpty()) {
            int v = queue.poll();
            if (isTarget[v]) return v;
            for (int e = g.outStart[v]; e < g.outStart[v + 1]; e++) {
                if (cutCost[e] > 0) continue;
                int w = g.outTo[e];
                if (!seen[w]) { seen[w] = true; queue.add(w); }
            }
        }
        return -1;
    }

    /** Root-first path to target consisting solely of uncuttable references. */
    private static List<PathFinder.Step> evidencePath(HeapModel g, int target, long[] cutCost) {
        if (target < 0) return List.of();
        int n = g.nodeCount();
        int[] prevNode = new int[n];
        int[] prevEdge = new int[n];
        Arrays.fill(prevNode, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (g.root[i]) { prevNode[i] = i; queue.add(i); }
        }
        while (!queue.isEmpty()) {
            int v = queue.poll();
            if (v == target) break;
            for (int e = g.outStart[v]; e < g.outStart[v + 1]; e++) {
                if (cutCost[e] > 0) continue;
                int w = g.outTo[e];
                if (prevNode[w] == -1) {
                    prevNode[w] = v;
                    prevEdge[w] = e;
                    queue.add(w);
                }
            }
        }
        List<PathFinder.Step> steps = new ArrayList<>();
        int cur = target;
        while (prevNode[cur] != cur) {
            int p = prevNode[cur];
            steps.add(new PathFinder.Step(g.ids[p], g.classNames[p], g.outLabel[prevEdge[cur]],
                    g.ids[cur], g.classNames[cur]));
            cur = p;
        }
        Collections.reverse(steps);
        return steps;
    }

    /** Dinic max-flow with long capacities. The augmenting-path DFS is
     *  recursive and graph depth can reach the node count, so it runs on a
     *  worker thread with a large stack. */
    private static final class Dinic {
        private static final class Arc {
            final int to, rev;
            long cap;
            Arc(int to, int rev, long cap) { this.to = to; this.rev = rev; this.cap = cap; }
        }

        private final ArrayList<Arc>[] adj;
        private final int[] level;
        private final int[] next;
        private int source, sink;

        @SuppressWarnings("unchecked")
        Dinic(int n) {
            adj = new ArrayList[n];
            for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
            level = new int[n];
            next = new int[n];
        }

        void addArc(int u, int v, long cap) {
            adj[u].add(new Arc(v, adj[v].size(), cap));
            adj[v].add(new Arc(u, adj[u].size() - 1, 0));
        }

        long maxFlow(int s, int t) {
            this.source = s;
            this.sink = t;
            long[] out = new long[1];
            RuntimeException[] error = new RuntimeException[1];
            Thread worker = new Thread(null, () -> {
                try {
                    long flow = 0;
                    while (bfs()) {
                        Arrays.fill(next, 0);
                        long f;
                        while ((f = dfs(s, Long.MAX_VALUE)) > 0) flow += f;
                    }
                    out[0] = flow;
                } catch (RuntimeException e) {
                    error[0] = e;
                }
            }, "heapx-cut-planner", 1L << 26);
            worker.start();
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("cut planning interrupted", e);
            }
            if (error[0] != null) throw error[0];
            return out[0];
        }

        private boolean bfs() {
            Arrays.fill(level, -1);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            level[source] = 0;
            queue.add(source);
            while (!queue.isEmpty()) {
                int v = queue.poll();
                for (Arc a : adj[v]) {
                    if (a.cap > 0 && level[a.to] < 0) {
                        level[a.to] = level[v] + 1;
                        queue.add(a.to);
                    }
                }
            }
            return level[sink] >= 0;
        }

        private long dfs(int v, long pushed) {
            if (v == sink || pushed == 0) return pushed;
            ArrayList<Arc> arcs = adj[v];
            while (next[v] < arcs.size()) {
                Arc a = arcs.get(next[v]);
                if (a.cap > 0 && level[a.to] == level[v] + 1) {
                    long f = dfs(a.to, Math.min(pushed, a.cap));
                    if (f > 0) {
                        a.cap -= f;
                        adj[a.to].get(a.rev).cap += f;
                        return f;
                    }
                }
                next[v]++;
            }
            return 0;
        }

        /** Nodes on the source side of the minimum cut (residual reachability). */
        boolean[] reachableFrom(int s) {
            boolean[] seen = new boolean[adj.length];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            seen[s] = true;
            queue.add(s);
            while (!queue.isEmpty()) {
                int v = queue.poll();
                for (Arc a : adj[v]) {
                    if (a.cap > 0 && !seen[a.to]) {
                        seen[a.to] = true;
                        queue.add(a.to);
                    }
                }
            }
            return seen;
        }
    }
}
