package heapx;

import heapx.graph.CutPlanner;
import heapx.model.HeapModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CutPlannerTest {

    private static List<CutPlanner.Candidate> candidates(DominatorAnalysisTest.G g,
                                                         HeapModel m, String[]... specs) {
        List<CutPlanner.Candidate> out = new ArrayList<>();
        for (String[] s : specs) {
            int from = g.idx.get(s[0]);
            int to = g.idx.get(s[2]);
            int edge = -1;
            for (int e = m.outStart[from]; e < m.outStart[from + 1]; e++) {
                if (m.outTo[e] == to && m.outLabel[e].equals(s[1])) edge = e;
            }
            assertTrue(edge >= 0, "test edge exists: " + String.join(",", s));
            out.add(new CutPlanner.Candidate(from, to, s[1], Long.parseLong(s[3]), edge));
        }
        return out;
    }

    private static int[] targets(DominatorAnalysisTest.G g, String... names) {
        int[] t = new int[names.length];
        for (int i = 0; i < names.length; i++) t[i] = g.idx.get(names[i]);
        return t;
    }

    @Test
    void sharedPrefixCutOnceServesBothTargets() {
        // r -> a -> {t1, t2}: cutting r->a (5) beats cutting a->t1 + a->t2 (3+3)
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 1).node("a", 1).node("t1", 1).node("t2", 1);
        g.root("r");
        g.edge("r", "C#f", "a").edge("a", "C#g", "t1").edge("a", "C#h", "t2");
        HeapModel m = g.build();
        var cands = candidates(g, m,
                new String[]{"r", "C#f", "a", "5"},
                new String[]{"a", "C#g", "t1", "3"},
                new String[]{"a", "C#h", "t2", "3"});
        CutPlanner.Result res = CutPlanner.plan(m, targets(g, "t1", "t2"), cands);
        assertTrue(res.feasible());
        assertEquals(5, res.totalCost());
        assertEquals(1, res.cuts().size());
        assertEquals("C#f", res.cuts().get(0).label());
    }

    @Test
    void cheapestCutIsNotShortestRootPath() {
        // shortest root path is the direct edge r->t (cost 100), but t stays
        // reachable via r->b->t: the optimal cut must also break b->t (cost 2),
        // an edge that lies on no shortest root path
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 1).node("b", 1).node("t", 1);
        g.root("r");
        g.edge("r", "C#direct", "t").edge("r", "C#via", "b").edge("b", "C#next", "t");
        HeapModel m = g.build();
        var cands = candidates(g, m,
                new String[]{"r", "C#direct", "t", "100"},
                new String[]{"b", "C#next", "t", "2"});
        CutPlanner.Result res = CutPlanner.plan(m, targets(g, "t"), cands);
        assertTrue(res.feasible());
        assertEquals(102, res.totalCost());
        assertEquals(2, res.cuts().size());
    }

    @Test
    void cycleAndTwoFieldsBetweenSameObjects() {
        // r -> x ; x <-> y ; y -> t ; plus r -f1-> t and r -f2-> t (parallel fields)
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 1).node("x", 1).node("y", 1).node("t", 1);
        g.root("r");
        g.edge("r", "C#e", "x").edge("x", "C#e", "y").edge("y", "C#e", "x")
         .edge("y", "C#out", "t").edge("r", "C#f1", "t").edge("r", "C#f2", "t");
        HeapModel m = g.build();
        var cands = candidates(g, m,
                new String[]{"y", "C#out", "t", "1"},
                new String[]{"r", "C#f1", "t", "3"},
                new String[]{"r", "C#f2", "t", "4"});
        CutPlanner.Result res = CutPlanner.plan(m, targets(g, "t"), cands);
        assertTrue(res.feasible());
        assertEquals(1 + 3 + 4, res.totalCost()); // all three entries must be cut
        assertEquals(3, res.cuts().size());
    }

    @Test
    void multipleRootsAllDisconnected() {
        // r1 -> s <- r2 ; t reachable only via s
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r1", 1).node("r2", 1).node("s", 1).node("t", 1);
        g.root("r1").root("r2");
        g.edge("r1", "C#a", "s").edge("r2", "C#b", "s").edge("s", "C#c", "t");
        HeapModel m = g.build();
        var cands = candidates(g, m,
                new String[]{"r1", "C#a", "s", "9"},
                new String[]{"r2", "C#b", "s", "9"},
                new String[]{"s", "C#c", "t", "2"});
        CutPlanner.Result res = CutPlanner.plan(m, targets(g, "t"), cands);
        assertTrue(res.feasible());
        assertEquals(2, res.totalCost()); // one shared downstream edge beats two root edges
    }

    @Test
    void infeasibleYieldsUncuttableEvidencePath() {
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 1).node("m", 1).node("t", 1);
        g.root("r");
        g.edge("r", "C#first", "m").edge("m", "C#second", "t");
        HeapModel m = g.build();
        CutPlanner.Result res = CutPlanner.plan(m, targets(g, "t"), List.of());
        assertFalse(res.feasible());
        assertEquals(g.idx.get("t"), res.evidenceTarget());
        assertEquals(2, res.evidence().size()); // whole path is uncuttable
        assertEquals("C#first", res.evidence().get(0).via());
        assertEquals("C#second", res.evidence().get(1).via());
    }

    @Test
    void rootAsTargetIsExplainedWithEmptyPath() {
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 1).node("t", 1);
        g.root("r");
        g.edge("r", "C#f", "t");
        HeapModel m = g.build();
        CutPlanner.Result res = CutPlanner.plan(m, targets(g, "r"), List.of());
        assertFalse(res.feasible());
        assertEquals(g.idx.get("r"), res.evidenceTarget());
        assertTrue(res.evidence().isEmpty());
        assertTrue(m.root[res.evidenceTarget()]);
    }

    @Test
    void alreadyUnreachableTargetCostsNothing() {
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 1).node("live", 1).node("dead", 1);
        g.root("r");
        g.edge("r", "C#f", "live");
        HeapModel m = g.build();
        CutPlanner.Result res = CutPlanner.plan(m, targets(g, "dead"), List.of());
        assertTrue(res.feasible());
        assertEquals(0, res.totalCost());
        assertTrue(res.cuts().isEmpty());
    }

    @Test
    void newlyUnreachableIsReachableSetDifference() {
        // r -> a -> b ; dead chain d1 -> d2 never reachable
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 10).node("a", 20).node("b", 30).node("d1", 40).node("d2", 50);
        g.root("r");
        g.edge("r", "C#f", "a").edge("a", "C#g", "b").edge("d1", "C#h", "d2");
        HeapModel m = g.build();
        var cands = candidates(g, m, new String[]{"r", "C#f", "a", "1"});
        CutPlanner.Result res = CutPlanner.plan(m, targets(g, "b"), cands);
        assertTrue(res.feasible());
        boolean[] removed = new boolean[m.edgeCount()];
        for (CutPlanner.Candidate c : res.cuts()) removed[c.edgeIndex()] = true;
        boolean[] before = CutPlanner.reachableFrom(m, null);
        boolean[] after = CutPlanner.reachableFrom(m, removed);
        long bytes = 0;
        int count = 0;
        for (int i = 0; i < m.nodeCount(); i++) {
            if (before[i] && !after[i]) { count++; bytes += m.shallow[i]; }
        }
        assertEquals(2, count);          // a and b, not the pre-unreachable d1/d2
        assertEquals(20 + 30, bytes);
        assertTrue(after[g.idx.get("r")]);
    }
}
