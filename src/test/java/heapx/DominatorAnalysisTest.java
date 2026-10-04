package heapx;

import heapx.graph.DominatorAnalysis;
import heapx.graph.PathFinder;
import heapx.model.HeapModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DominatorAnalysisTest {

    /** Small graph builder keyed by symbolic names. */
    static final class G {
        List<String> names = new ArrayList<>();
        Map<String, Integer> idx = new HashMap<>();
        Map<String, Long> sizes = new HashMap<>();
        List<String> roots = new ArrayList<>();
        List<String[]> edges = new ArrayList<>(); // from,label,to

        G node(String name, long shallow) { idx.put(name, names.size()); names.add(name); sizes.put(name, shallow); return this; }
        G root(String name) { roots.add(name); return this; }
        G edge(String from, String label, String to) { edges.add(new String[]{from, label, to}); return this; }

        HeapModel build() {
            int n = names.size();
            long[] ids = new long[n];
            String[] cls = new String[n];
            long[] sh = new long[n];
            boolean[] rt = new boolean[n];
            for (int i = 0; i < n; i++) {
                ids[i] = 0x1000L + i;
                cls[i] = "C";
                sh[i] = sizes.get(names.get(i));
            }
            for (String r : roots) rt[idx.get(r)] = true;
            int m = edges.size();
            int[] outStart = new int[n + 1], inStart = new int[n + 1];
            int[] outTo = new int[m], inFrom = new int[m];
            String[] outLabel = new String[m], inLabel = new String[m];
            for (String[] e : edges) { outStart[idx.get(e[0]) + 1]++; inStart[idx.get(e[2]) + 1]++; }
            for (int i = 0; i < n; i++) { outStart[i + 1] += outStart[i]; inStart[i + 1] += inStart[i]; }
            int[] oc = outStart.clone(), ic = inStart.clone();
            for (String[] e : edges) {
                int f = idx.get(e[0]), t = idx.get(e[2]);
                outTo[oc[f]] = t; outLabel[oc[f]++] = e[1];
                inFrom[ic[t]] = f; inLabel[ic[t]++] = e[1];
            }
            return new HeapModel(ids, cls, sh, rt, outStart, outTo, outLabel, inStart, inFrom, inLabel);
        }
    }

    @Test
    void chainRetainedIsSubtreeSumNotReachableSum() {
        // root r -> a -> b ; shared also reachable from r directly
        G g = new G();
        g.node("r", 10).node("a", 20).node("b", 30).node("shared", 40);
        g.root("r");
        g.edge("r", "f1", "a").edge("a", "f2", "b").edge("a", "f3", "shared").edge("r", "f4", "shared");
        HeapModel m = g.build();
        DominatorAnalysis da = DominatorAnalysis.compute(m);
        int r = g.idx.get("r"), a = g.idx.get("a"), b = g.idx.get("b"), s = g.idx.get("shared");
        // shared is reachable from a but NOT dominated by a (r -> shared directly)
        assertEquals(10 + 20 + 30 + 40, da.retained[r]);
        assertEquals(20 + 30, da.retained[a]); // shared excluded: not dominated
        assertEquals(30, da.retained[b]);
        assertEquals(40, da.retained[s]);
        assertEquals(r, da.idom[a]);
        assertEquals(a, da.idom[b]);
        assertEquals(r, da.idom[s]);
    }

    @Test
    void cycleIsDominatedByEntryPoint() {
        // root -> x ; x <-> y cycle
        G g = new G();
        g.node("root", 1).node("x", 2).node("y", 4);
        g.root("root");
        g.edge("root", "f", "x").edge("x", "f", "y").edge("y", "f", "x");
        HeapModel m = g.build();
        DominatorAnalysis da = DominatorAnalysis.compute(m);
        int root = g.idx.get("root"), x = g.idx.get("x"), y = g.idx.get("y");
        assertEquals(root, da.idom[x]);
        assertEquals(x, da.idom[y]);
        assertEquals(7, da.retained[root]);
        assertEquals(6, da.retained[x]);
        assertEquals(4, da.retained[y]);
    }

    @Test
    void multipleRootsShareNode() {
        // r1 -> s, r2 -> s : s's idom is the virtual root
        G g = new G();
        g.node("r1", 1).node("r2", 1).node("s", 8);
        g.root("r1").root("r2");
        g.edge("r1", "f", "s").edge("r2", "f", "s");
        HeapModel m = g.build();
        DominatorAnalysis da = DominatorAnalysis.compute(m);
        int s = g.idx.get("s");
        assertEquals(da.virtualRoot, da.idom[s]);
        assertEquals(1, da.retained[g.idx.get("r1")]);
        assertEquals(1, da.retained[g.idx.get("r2")]);
        assertEquals(8, da.retained[s]);
    }

    @Test
    void unreachableMarkedAndExcluded() {
        G g = new G();
        g.node("r", 1).node("live", 2).node("dead1", 4).node("dead2", 8);
        g.root("r");
        g.edge("r", "f", "live").edge("dead1", "f", "dead2");
        HeapModel m = g.build();
        DominatorAnalysis da = DominatorAnalysis.compute(m);
        assertFalse(da.unreachable[g.idx.get("live")]);
        assertTrue(da.unreachable[g.idx.get("dead1")]);
        assertTrue(da.unreachable[g.idx.get("dead2")]);
        assertEquals(3, da.retained[g.idx.get("r")]);
        assertNull(PathFinder.shortestPathFromRoot(m, da, g.idx.get("dead1")));
    }

    @Test
    void shortestPathUsesRealEdgesWithLabels() {
        G g = new G();
        g.node("r", 1).node("a", 1).node("x", 1).node("b", 1).node("t", 1);
        g.root("r");
        g.edge("r", "C#longWay", "a").edge("a", "C#next", "x").edge("x", "C#tail", "t")
         .edge("r", "C#direct", "b").edge("b", "[3]", "t");
        HeapModel m = g.build();
        DominatorAnalysis da = DominatorAnalysis.compute(m);
        List<PathFinder.Step> path = PathFinder.shortestPathFromRoot(m, da, g.idx.get("t"));
        assertNotNull(path);
        assertEquals(2, path.size());
        assertEquals("C#direct", path.get(0).via());
        assertEquals("[3]", path.get(1).via());
        assertEquals(m.ids[g.idx.get("r")], path.get(0).fromId());
        assertEquals(m.ids[g.idx.get("t")], path.get(1).toId());
    }
}
