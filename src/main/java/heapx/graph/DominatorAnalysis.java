package heapx.graph;

import heapx.model.HeapModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immediate-dominator analysis over the object graph plus a virtual root
 * whose children are all GC roots / static-reference targets. A node
 * dominates another when every path from the virtual root to the latter
 * passes through it. Retained bytes = sum of shallow sizes of the
 * dominated subtree (never a mere reachable-size sum). Unreachable nodes
 * are flagged and excluded from retained rankings.
 *
 * Algorithm: Cooper, Harvey, Kennedy iterative dataflow over reverse
 * postorder. Handles cycles, shared subgraphs and multiple roots.
 */
public final class DominatorAnalysis {
    public final int virtualRoot;          // node index of the virtual root (= nodeCount)
    public final int[] idom;               // immediate dominator per node, -1 = unreachable
    public final long[] retained;          // retained bytes per node (dominator subtree shallow sum)
    public final boolean[] unreachable;

    private DominatorAnalysis(int n) {
        this.virtualRoot = n;
        this.idom = new int[n + 1];
        this.retained = new long[n + 1];
        this.unreachable = new boolean[n];
    }

    public static DominatorAnalysis compute(HeapModel g) {
        int n = g.nodeCount();
        DominatorAnalysis da = new DominatorAnalysis(n);
        int total = n + 1;

        // Predecessor lists including virtual-root edges (virtual -> each root).
        // Build reverse CSR on the fly: reuse g.inStart/inFrom, plus roots list.
        List<Integer> roots = new ArrayList<>();
        for (int i = 0; i < n; i++) if (g.root[i]) roots.add(i);

        // Reverse postorder from the virtual root (iterative DFS).
        int[] rpo = new int[total];          // rpo order -> node
        int[] rpoNum = new int[total];       // node -> rpo number, -1 unreachable
        Arrays.fill(rpoNum, -1);
        int[] state = new int[total];        // DFS cursor per node
        int[] stack = new int[total];
        int sp = 0;
        stack[sp++] = da.virtualRoot;
        int order = 0;
        // iterative DFS producing postorder, then reversed
        int[] post = new int[total];
        int postCount = 0;
        boolean[] onStack = new boolean[total];
        boolean[] visited = new boolean[total];
        visited[da.virtualRoot] = true;
        while (sp > 0) {
            int v = stack[sp - 1];
            int next = -1;
            if (v == da.virtualRoot) {
                while (state[v] < roots.size()) {
                    int w = roots.get(state[v]++);
                    if (!visited[w]) { next = w; break; }
                }
            } else {
                while (state[v] < g.outStart[v + 1] - g.outStart[v]) {
                    int w = g.outTo[g.outStart[v] + state[v]];
                    state[v]++;
                    if (!visited[w]) { next = w; break; }
                }
            }
            if (next >= 0) {
                visited[next] = true;
                stack[sp++] = next;
            } else {
                post[postCount++] = v;
                sp--;
            }
        }
        for (int i = 0; i < postCount; i++) {
            int node = post[postCount - 1 - i];
            rpo[i] = node;
            rpoNum[node] = i;
        }

        Arrays.fill(da.idom, -1);
        da.idom[da.virtualRoot] = da.virtualRoot;

        // iterate to fixed point
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < postCount; i++) { // skip virtual root (rpo 0)
                int b = rpo[i];
                int newIdom = -1;
                // first processed predecessor
                for (int p = g.inStart[b]; p < g.inStart[b + 1]; p++) {
                    int pred = g.inFrom[p];
                    if (rpoNum[pred] >= 0 && da.idom[pred] != -1) { newIdom = pred; break; }
                }
                if (g.root[b] && da.idom[da.virtualRoot] != -1) {
                    if (newIdom == -1) newIdom = da.virtualRoot;
                }
                if (newIdom == -1) continue; // no processed preds yet
                for (int p = g.inStart[b]; p < g.inStart[b + 1]; p++) {
                    int pred = g.inFrom[p];
                    if (pred == newIdom || rpoNum[pred] < 0 || da.idom[pred] == -1) continue;
                    newIdom = intersect(newIdom, pred, rpoNum, da.idom);
                }
                if (g.root[b]) {
                    newIdom = intersect(newIdom, da.virtualRoot, rpoNum, da.idom);
                }
                if (da.idom[b] != newIdom) {
                    da.idom[b] = newIdom;
                    changed = true;
                }
            }
        }

        for (int i = 0; i < n; i++) {
            da.unreachable[i] = rpoNum[i] < 0;
            if (da.unreachable[i]) da.idom[i] = -1;
        }

        // retained = own shallow + sum of children's retained, processed in
        // reverse RPO so children (higher rpo) are accumulated before parents.
        for (int i = 0; i < n; i++) if (!da.unreachable[i]) da.retained[i] = g.shallow[i];
        for (int i = postCount - 1; i >= 1; i--) {
            int v = rpo[i];
            da.retained[da.idom[v]] += da.retained[v];
        }
        return da;
    }

    private static int intersect(int a, int b, int[] rpoNum, int[] idom) {
        while (a != b) {
            while (rpoNum[a] > rpoNum[b]) a = idom[a];
            while (rpoNum[b] > rpoNum[a]) b = idom[b];
        }
        return a;
    }
}
