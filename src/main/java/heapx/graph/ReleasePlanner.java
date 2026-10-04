package heapx.graph;

import heapx.model.HeapModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimum-cost strong-reference cut planning. Given target objects and a
 * whitelist of severable references (each with a positive cost), finds the
 * cheapest set of references to null out so that every target becomes
 * unreachable from all GC roots / static-reference targets.
 *
 * Modelled exactly as a minimum s-t cut (never per-target greedy): a virtual
 * source connects to every root with infinite capacity, non-candidate graph
 * edges have infinite capacity, candidate edges carry their cost, and every
 * reachable target connects to the sink with infinite capacity. The min cut
 * therefore consists only of candidate edges and jointly disconnects all
 * targets, correctly handling shared paths, multiple roots, cycles and
 * parallel fields between the same objects. Solved with Dinic's algorithm.
 *
 * The planner is read-only: it never mutates the HeapModel.
 */
public final class ReleasePlanner {

    /** A validated, severable reference. edge indexes the forward CSR. */
    public record Candidate(long fromId, String via, long toId, long cost,
                            int fromIdx, int toIdx, int edge) {}

    public record CutEdge(long fromId, String via, long toId, long cost) {}

    public record EvidenceStep(long fromId, String fromClass, String via, long toId, String toClass) {}

    public static final class Plan {
        public boolean feasible;
        public String reason;                       // set when infeasible
        public long evidenceTargetId;               // target that cannot be released
        public boolean evidenceTargetIsRoot;
        public List<EvidenceStep> evidence = List.of(); // root-to-target path, non-candidate edges only
        public long totalCost;
        public List<CutEdge> cuts = List.of();
        public List<Long> newlyUnreachableIds = List.of();
        public long newlyUnreachableBytes;
    }

    public static Plan plan(HeapModel g, DominatorAnalysis da, int[] targets, List<Candidate> candidates) {
        int n = g.nodeCount();
        long[] candidateCost = new long[g.edgeCount()];
        Arrays.fill(candidateCost, -1);
        for (Candidate c : candidates) candidateCost[c.edge()] = c.cost();

        Plan p = new Plan();

        // Roots are pinned: a root target can never become unreachable.
        for (int t : targets) {
            if (g.root[t]) {
                p.feasible = false;
                p.evidenceTargetId = g.ids[t];
                p.evidenceTargetIsRoot = true;
                p.reason = "target is itself a GC root / static-reference target and is pinned reachable";
                return p;
            }
        }

        // Feasibility: with every candidate edge removed, no target may remain
        // reachable. BFS from roots over non-candidate edges only.
        int[] prevNode = new int[n];
        int[] prevEdge = new int[n];
        Arrays.fill(prevNode, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (g.root[i]) { prevNode[i] = i; queue.add(i); }
        }
        while (!queue.isEmpty()) {
            int v = queue.poll();
            for (int e = g.outStart[v]; e < g.outStart[v + 1]; e++) {
                if (candidateCost[e] >= 0) continue; // severable, not evidence-grade
                int w = g.outTo[e];
                if (prevNode[w] == -1) {
                    prevNode[w] = v;
                    prevEdge[w] = e;
                    queue.add(w);
                }
            }
        }
        for (int t : targets) {
            if (da.unreachable[t]) continue; // already unreachable: free
            if (prevNode[t] == -1) continue; // disconnectable
            p.feasible = false;
            p.evidenceTargetId = g.ids[t];
            p.reason = "target stays reachable through non-severable references only";
            p.evidence = reconstruct(g, prevNode, prevEdge, t);
            return p;
        }

        // Min s-t cut via Dinic. Source = n, sink = n + 1.
        int source = n, sink = n + 1;
        Dinic dinic = new Dinic(n + 2);
        long totalCandidateCost = 0;
        for (Candidate c : candidates) totalCandidateCost += c.cost();
        long inf = totalCandidateCost + 1; // any cut with an INF edge beats all candidates combined
        for (int i = 0; i < n; i++) if (g.root[i]) dinic.add(source, i, inf);
        for (int v = 0; v < n; v++) {
            for (int e = g.outStart[v]; e < g.outStart[v + 1]; e++) {
                long cap = candidateCost[e] >= 0 ? candidateCost[e] : inf;
                dinic.add(v, g.outTo[e], cap);
            }
        }
        for (int t : targets) {
            if (!da.unreachable[t]) dinic.add(t, sink, inf);
        }
        long minCut = dinic.maxflow(source, sink);

        // Extract the cut: candidate edges from the source side to the sink side.
        boolean[] sourceSide = dinic.reachableFrom(source);
        boolean[] cutEdge = new boolean[g.edgeCount()];
        List<CutEdge> cuts = new ArrayList<>();
        long cost = 0;
        for (Candidate c : candidates) {
            if (sourceSide[c.fromIdx()] && !sourceSide[c.toIdx()]) {
                cuts.add(new CutEdge(c.fromId(), c.via(), c.toId(), c.cost()));
                cutEdge[c.edge()] = true;
                cost += c.cost();
            }
        }
        if (cost != minCut) throw new IllegalStateException("cut extraction mismatch");

        // Newly unreachable = reachable before minus reachable after the cut.
        boolean[] still = new boolean[n];
        ArrayDeque<Integer> bfs = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (g.root[i]) { still[i] = true; bfs.add(i); }
        }
        while (!bfs.isEmpty()) {
            int v = bfs.poll();
            for (int e = g.outStart[v]; e < g.outStart[v + 1]; e++) {
                if (cutEdge[e]) continue;
                int w = g.outTo[e];
                if (!still[w]) { still[w] = true; bfs.add(w); }
            }
        }
        List<Long> newly = new ArrayList<>();
        long bytes = 0;
        for (int i = 0; i < n; i++) {
            if (!da.unreachable[i] && !still[i]) {
                newly.add(g.ids[i]);
                bytes += g.shallow[i];
            }
        }

        p.feasible = true;
        p.totalCost = cost;
        p.cuts = cuts;
        p.newlyUnreachableIds = newly;
        p.newlyUnreachableBytes = bytes;
        return p;
    }

    private static List<EvidenceStep> reconstruct(HeapModel g, int[] prevNode, int[] prevEdge, int target) {
        List<EvidenceStep> steps = new ArrayList<>();
        int cur = target;
        while (prevNode[cur] != cur) {
            int from = prevNode[cur];
            steps.add(new EvidenceStep(g.ids[from], g.classNames[from],
                    g.outLabel[prevEdge[cur]], g.ids[cur], g.classNames[cur]));
            cur = from;
        }
        List<EvidenceStep> ordered = new ArrayList<>(steps.size());
        for (int i = steps.size() - 1; i >= 0; i--) ordered.add(steps.get(i));
        return ordered;
    }

    /** Dinic max-flow with long capacities. */
    static final class Dinic {
        private static final class Edge {
            final int to;
            final int rev;
            long cap;
            Edge(int to, int rev, long cap) { this.to = to; this.rev = rev; this.cap = cap; }
        }

        private final List<Edge>[] adj;
        private final int[] level;
        private final int[] iter;

        @SuppressWarnings("unchecked")
        Dinic(int nodes) {
            adj = new List[nodes];
            for (int i = 0; i < nodes; i++) adj[i] = new ArrayList<>();
            level = new int[nodes];
            iter = new int[nodes];
        }

        void add(int from, int to, long cap) {
            adj[from].add(new Edge(to, adj[to].size(), cap));
            adj[to].add(new Edge(from, adj[from].size() - 1, 0));
        }

        long maxflow(int s, int t) {
            long flow = 0;
            while (bfs(s, t)) {
                Arrays.fill(iter, 0);
                long f;
                while ((f = dfs(s, t, Long.MAX_VALUE)) > 0) flow += f;
            }
            return flow;
        }

        private boolean bfs(int s, int t) {
            Arrays.fill(level, -1);
            ArrayDeque<Integer> q = new ArrayDeque<>();
            level[s] = 0;
            q.add(s);
            while (!q.isEmpty()) {
                int v = q.poll();
                for (Edge e : adj[v]) {
                    if (e.cap > 0 && level[e.to] < 0) {
                        level[e.to] = level[v] + 1;
                        q.add(e.to);
                    }
                }
            }
            return level[t] >= 0;
        }

        private long dfs(int v, int t, long f) {
            if (v == t) return f;
            for (; iter[v] < adj[v].size(); iter[v]++) {
                Edge e = adj[v].get(iter[v]);
                if (e.cap > 0 && level[v] < level[e.to]) {
                    long d = dfs(e.to, t, Math.min(f, e.cap));
                    if (d > 0) {
                        e.cap -= d;
                        adj[e.to].get(e.rev).cap += d;
                        return d;
                    }
                }
            }
            return 0;
        }

        /** Nodes reachable from s via edges with residual capacity above zero. */
        boolean[] reachableFrom(int s) {
            boolean[] seen = new boolean[adj.length];
            ArrayDeque<Integer> q = new ArrayDeque<>();
            seen[s] = true;
            q.add(s);
            while (!q.isEmpty()) {
                int v = q.poll();
                for (Edge e : adj[v]) {
                    if (e.cap > 0 && !seen[e.to]) {
                        seen[e.to] = true;
                        q.add(e.to);
                    }
                }
            }
            return seen;
        }
    }
}
