package heapx;

import heapx.graph.DominatorAnalysis;
import heapx.graph.ReleasePlanner;
import heapx.model.HeapModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReleasePlannerTest {

    private static ReleasePlanner.Candidate cand(HeapModel m, DominatorAnalysisTest.G g,
                                                 String from, String via, String to, long cost) {
        int f = g.idx.get(from), t = g.idx.get(to);
        for (int e = m.outStart[f]; e < m.outStart[f + 1]; e++) {
            if (m.outTo[e] == t && m.outLabel[e].equals(via)) {
                return new ReleasePlanner.Candidate(m.ids[f], via, m.ids[t], cost, f, t, e);
            }
        }
        fail("no edge " + from + " --" + via + "--> " + to);
        return null;
    }

    private static int[] targets(DominatorAnalysisTest.G g, String... names) {
        int[] t = new int[names.length];
        for (int i = 0; i < names.length; i++) t[i] = g.idx.get(names[i]);
        return t;
    }

    @Test
    void sharedPrefixCutOnceBeatsPerTargetUnion() {
        // r -> m (cost 5) ; m -> t1, m -> t2 ; r -> t1 (cost 4), r -> t2 (cost 4)
        // Per-target greedy union = 4+4=8 (or worse); joint optimum = 5+... no:
        // cutting r->m alone leaves r->t1/r->t2, so optimum = 5+4+4? No:
        // cut {r->m, r->t1, r->t2} = 13 vs cut {r->t1, r->t2} = 8 vs cut {m->t1, m->t2, ...}.
        // Make shared edge cheap: r->m cost 1, r->t1 cost 4, r->t2 cost 4 -> optimum 1+4+4=9?
        // Simpler: only paths are r->m->t1 and r->m->t2 plus expensive directs.
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 1).node("m", 1).node("t1", 10).node("t2", 20);
        g.root("r");
        g.edge("r", "shared", "m").edge("m", "a", "t1").edge("m", "b", "t2")
         .edge("r", "direct1", "t1").edge("r", "direct2", "t2");
        HeapModel m = g.build();
        DominatorAnalysis da = DominatorAnalysis.compute(m);
        List<ReleasePlanner.Candidate> cands = List.of(
                cand(m, g, "r", "shared", "m", 1),
                cand(m, g, "r", "direct1", "t1", 4),
                cand(m, g, "r", "direct2", "t2", 4));
        ReleasePlanner.Plan p = ReleasePlanner.plan(m, da, targets(g, "t1", "t2"), cands);
        assertTrue(p.feasible);
        assertEquals(9, p.totalCost);
        assertEquals(3, p.cuts.size());
        assertTrue(p.newlyUnreachableIds.contains(m.ids[g.idx.get("t1")]));
        assertTrue(p.newlyUnreachableIds.contains(m.ids[g.idx.get("t2")]));
        // m also becomes unreachable and is counted with its shallow bytes
        assertTrue(p.newlyUnreachableIds.contains(m.ids[g.idx.get("m")]));
        assertEquals(10 + 20 + 1, p.newlyUnreachableBytes);
    }

    @Test
    void sharedEdgeCutOnceForTwoTargets() {
        // r -> m -> t1, m -> t2 ; only r->m (cost 3) severable -> single cut frees both
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 1).node("m", 2).node("t1", 10).node("t2", 20);
        g.root("r");
        g.edge("r", "shared", "m").edge("m", "a", "t1").edge("m", "b", "t2");
        HeapModel m = g.build();
        DominatorAnalysis da = DominatorAnalysis.compute(m);
        ReleasePlanner.Plan p = ReleasePlanner.plan(m, da, targets(g, "t1", "t2"),
                List.of(cand(m, g, "r", "shared", "m", 3)));
        assertTrue(p.feasible);
        assertEquals(3, p.totalCost);
        assertEquals(1, p.cuts.size());
        assertEquals(3, p.newlyUnreachableIds.size()); // m, t1, t2
        assertEquals(32, p.newlyUnreachableBytes);
    }

    @Test
    void cycleAndMultipleRootsHandled() {
        // r1 -> x ; x <-> y ; y -> t ; r2 -> t (cost 7) ; x->y cost 2, y->x cost 3, y->t cost 4
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r1", 1).node("r2", 1).node("x", 1).node("y", 1).node("t", 50);
        g.root("r1").root("r2");
        g.edge("r1", "e", "x").edge("x", "cyc", "y").edge("y", "back", "x")
         .edge("y", "out", "t").edge("r2", "direct", "t");
        HeapModel m = g.build();
        DominatorAnalysis da = DominatorAnalysis.compute(m);
        List<ReleasePlanner.Candidate> cands = List.of(
                cand(m, g, "x", "cyc", "y", 2),
                cand(m, g, "y", "back", "x", 3),
                cand(m, g, "y", "out", "t", 4),
                cand(m, g, "r2", "direct", "t", 7));
        ReleasePlanner.Plan p = ReleasePlanner.plan(m, da, targets(g, "t"), cands);
        assertTrue(p.feasible);
        // cheapest: cut y->t (4) + r2->t (7) = 11 vs x->cyc(2)+r2->t(7)=9 vs ...
        assertEquals(9, p.totalCost);
        assertEquals(2, p.cuts.size());
    }

    @Test
    void parallelFieldsBetweenSameObjectsAreDistinct() {
        // r -> t via f1 (cost 1) and via f2 (cost 1): both must be cut
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 1).node("t", 9);
        g.root("r");
        g.edge("r", "f1", "t").edge("r", "f2", "t");
        HeapModel m = g.build();
        DominatorAnalysis da = DominatorAnalysis.compute(m);
        List<ReleasePlanner.Candidate> cands = List.of(
                cand(m, g, "r", "f1", "t", 1),
                cand(m, g, "r", "f2", "t", 1));
        ReleasePlanner.Plan p = ReleasePlanner.plan(m, da, targets(g, "t"), cands);
        assertTrue(p.feasible);
        assertEquals(2, p.totalCost);
        assertEquals(2, p.cuts.size());
    }

    @Test
    void infeasibleWithNonCandidateEvidencePath() {
        // r -> a -> t ; only r->a severable... make r->a non-candidate: no candidates at all
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 1).node("a", 1).node("t", 5);
        g.root("r");
        g.edge("r", "e1", "a").edge("a", "e2", "t");
        HeapModel m = g.build();
        DominatorAnalysis da = DominatorAnalysis.compute(m);
        ReleasePlanner.Plan p = ReleasePlanner.plan(m, da, targets(g, "t"), List.of());
        assertFalse(p.feasible);
        assertFalse(p.evidenceTargetIsRoot);
        assertEquals(m.ids[g.idx.get("t")], p.evidenceTargetId);
        assertEquals(2, p.evidence.size());
        assertEquals("e1", p.evidence.get(0).via());
        assertEquals("e2", p.evidence.get(1).via());
        assertEquals(m.ids[g.idx.get("r")], p.evidence.get(0).fromId());
    }

    @Test
    void rootTargetIsPinnedAndExplained() {
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 1).node("t", 5);
        g.root("r");
        g.edge("r", "e", "t");
        HeapModel m = g.build();
        DominatorAnalysis da = DominatorAnalysis.compute(m);
        ReleasePlanner.Plan p = ReleasePlanner.plan(m, da, targets(g, "r"),
                List.of(cand(m, g, "r", "e", "t", 1)));
        assertFalse(p.feasible);
        assertTrue(p.evidenceTargetIsRoot);
        assertTrue(p.reason.contains("root"));
    }

    @Test
    void alreadyUnreachableTargetIsFree() {
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 1).node("live", 1).node("dead", 7);
        g.root("r");
        g.edge("r", "e", "live");
        HeapModel m = g.build();
        DominatorAnalysis da = DominatorAnalysis.compute(m);
        assertTrue(da.unreachable[g.idx.get("dead")]);
        ReleasePlanner.Plan p = ReleasePlanner.plan(m, da, targets(g, "dead"), List.of());
        assertTrue(p.feasible);
        assertEquals(0, p.totalCost);
        assertTrue(p.cuts.isEmpty());
        assertTrue(p.newlyUnreachableIds.isEmpty()); // was already unreachable: not counted
        assertEquals(0, p.newlyUnreachableBytes);
    }

    @Test
    void newlyUnreachableExcludesPreviouslyUnreachable() {
        // r -> a -> t ; dead chain d1->d2 unreachable from roots
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 1).node("a", 2).node("t", 4).node("d1", 8).node("d2", 16);
        g.root("r");
        g.edge("r", "e", "a").edge("a", "victim", "t").edge("d1", "x", "d2");
        HeapModel m = g.build();
        DominatorAnalysis da = DominatorAnalysis.compute(m);
        ReleasePlanner.Plan p = ReleasePlanner.plan(m, da, targets(g, "t"),
                List.of(cand(m, g, "a", "victim", "t", 6)));
        assertTrue(p.feasible);
        assertEquals(6, p.totalCost);
        List<Long> ids = new ArrayList<>(p.newlyUnreachableIds);
        assertTrue(ids.contains(m.ids[g.idx.get("t")]));
        assertFalse(ids.contains(m.ids[g.idx.get("d1")]));
        assertFalse(ids.contains(m.ids[g.idx.get("d2")]));
        assertFalse(ids.contains(m.ids[g.idx.get("a")])); // a still reachable via r
        assertEquals(4, p.newlyUnreachableBytes);
    }

    @Test
    void cheapestEdgeChosenNotShortestPath() {
        // Shortest root path to t is the direct edge r->t (cost 100); a longer
        // path r->m->t exists with a cheap final hop (cost 1, r->m not severable).
        // Cutting only the shortest path would leave t reachable: every path must
        // be cut, so the optimum is 100 + 1.
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 1).node("m", 1).node("t", 5);
        g.root("r");
        g.edge("r", "direct", "t").edge("r", "e", "m").edge("m", "weak", "t");
        HeapModel m = g.build();
        DominatorAnalysis da = DominatorAnalysis.compute(m);
        List<ReleasePlanner.Candidate> cands = List.of(
                cand(m, g, "r", "direct", "t", 100),
                cand(m, g, "m", "weak", "t", 1));
        ReleasePlanner.Plan p = ReleasePlanner.plan(m, da, targets(g, "t"), cands);
        assertTrue(p.feasible);
        assertEquals(101, p.totalCost);
        assertEquals(2, p.cuts.size());
        // m stays reachable; only t is newly unreachable
        assertEquals(List.of(m.ids[g.idx.get("t")]), p.newlyUnreachableIds);
        assertEquals(5, p.newlyUnreachableBytes);
    }

    @Test
    void cheapSharedBottleneckPreferredOverExpensiveDirects() {
        // r -> m (cost 1) -> t1, t2 ; plus expensive directs r->t1, r->t2 (cost 50 each).
        // Optimum: cut the shared bottleneck plus... directs still reach t1/t2, so the
        // optimum cuts the two directs only when cheaper: min(1, ...) per target jointly
        // = cut shared(1) is useless while directs exist; optimum = 50+50 vs 1+50+50.
        // Instead: no directs, shared bottleneck alone (covered elsewhere). Here verify
        // the planner picks the 1-cost shared edge plus nothing else when directs are absent
        // but each target also has its own pricey private edge from m.
        DominatorAnalysisTest.G g = new DominatorAnalysisTest.G();
        g.node("r", 1).node("m", 1).node("t1", 10).node("t2", 20);
        g.root("r");
        g.edge("r", "shared", "m").edge("m", "p1", "t1").edge("m", "p2", "t2");
        HeapModel m = g.build();
        DominatorAnalysis da = DominatorAnalysis.compute(m);
        List<ReleasePlanner.Candidate> cands = List.of(
                cand(m, g, "r", "shared", "m", 1),
                cand(m, g, "m", "p1", "t1", 50),
                cand(m, g, "m", "p2", "t2", 50));
        ReleasePlanner.Plan p = ReleasePlanner.plan(m, da, targets(g, "t1", "t2"), cands);
        assertTrue(p.feasible);
        assertEquals(1, p.totalCost); // one shared cut frees both targets
        assertEquals(1, p.cuts.size());
        assertEquals("shared", p.cuts.get(0).via());
        assertEquals(3, p.newlyUnreachableIds.size());
    }
}
