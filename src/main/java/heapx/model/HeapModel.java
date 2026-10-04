package heapx.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable object graph extracted from an HPROF heap dump.
 * Nodes: plain instances and arrays. Edges: instance reference fields
 * (inherited included) and object-array elements. Nulls and
 * java.lang.ref.Reference.referent are excluded by the parser.
 */
public final class HeapModel {
    public final long[] ids;            // object id per node index
    public final String[] classNames;   // class name per node
    public final long[] shallow;        // shallow heap bytes per node
    public final boolean[] root;        // GC root or class-static target
    // CSR forward adjacency
    public final int[] outStart;        // length nodeCount+1
    public final int[] outTo;           // edge target node index
    public final String[] outLabel;     // "declaring.Class#field" or "[index]"
    // CSR reverse adjacency (for root-path BFS)
    public final int[] inStart;
    public final int[] inFrom;
    public final String[] inLabel;

    private final Map<Long, Integer> byId;

    public HeapModel(long[] ids, String[] classNames, long[] shallow, boolean[] root,
                     int[] outStart, int[] outTo, String[] outLabel,
                     int[] inStart, int[] inFrom, String[] inLabel) {
        this.ids = ids;
        this.classNames = classNames;
        this.shallow = shallow;
        this.root = root;
        this.outStart = outStart;
        this.outTo = outTo;
        this.outLabel = outLabel;
        this.inStart = inStart;
        this.inFrom = inFrom;
        this.inLabel = inLabel;
        this.byId = new HashMap<>(ids.length * 2);
        for (int i = 0; i < ids.length; i++) byId.put(ids[i], i);
    }

    public int nodeCount() { return ids.length; }
    public int edgeCount() { return outTo.length; }

    public Integer indexOf(long objectId) { return byId.get(objectId); }
}
