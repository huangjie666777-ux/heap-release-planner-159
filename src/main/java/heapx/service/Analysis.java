package heapx.service;

import heapx.graph.DominatorAnalysis;
import heapx.model.HeapModel;

/** One completed, immutable heap analysis. Published atomically by id. */
public final class Analysis {
    public final String id;
    public final HeapModel model;
    public final DominatorAnalysis dominators;
    public final long createdAtMillis;

    public Analysis(String id, HeapModel model, DominatorAnalysis dominators) {
        this.id = id;
        this.model = model;
        this.dominators = dominators;
        this.createdAtMillis = System.currentTimeMillis();
    }
}
