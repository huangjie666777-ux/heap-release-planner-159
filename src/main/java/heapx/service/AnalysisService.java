package heapx.service;

import heapx.graph.DominatorAnalysis;
import heapx.model.HeapModel;
import heapx.parse.AnalysisException;
import heapx.parse.HprofParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parses uploads, runs dominator analysis and hands out independent
 * analysis ids. Failed or over-limit uploads publish nothing. Concurrent
 * uploads/queries are isolated; delete frees all resources. No persistence.
 */
public final class AnalysisService {
    public static final long MAX_BYTES = 100L * 1024 * 1024;
    public static final long MAX_OBJECTS =
            Long.parseLong(System.getenv().getOrDefault("HEAPX_MAX_OBJECTS", "50000"));
    public static final long MAX_EDGES =
            Long.parseLong(System.getenv().getOrDefault("HEAPX_MAX_EDGES", "200000"));

    private static final class TooLargeException extends RuntimeException {}

    private final Map<String, Analysis> analyses = new ConcurrentHashMap<>();
    private final HprofParser parser = new HprofParser(MAX_OBJECTS, MAX_EDGES);

    public Analysis analyze(InputStream in) throws AnalysisException {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("heapx-upload-", ".hprof");
            copyBounded(in, tmp);
            HeapModel model = parser.parse(tmp.toFile());
            DominatorAnalysis da = DominatorAnalysis.compute(model);
            Analysis a = new Analysis(UUID.randomUUID().toString(), model, da);
            analyses.put(a.id, a);
            return a;
        } catch (TooLargeException e) {
            throw new AnalysisException(422,
                    "file limit exceeded: upload is larger than " + MAX_BYTES + " bytes (100 MiB)");
        } catch (IOException e) {
            throw new AnalysisException(400, "failed to store upload: " + e.getMessage());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    private static void copyBounded(InputStream in, Path target) throws IOException {
        long total = 0;
        byte[] buf = new byte[1 << 16];
        try (var out = Files.newOutputStream(target)) {
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > MAX_BYTES) throw new TooLargeException();
                out.write(buf, 0, r);
            }
        }
    }

    public Analysis get(String id) { return analyses.get(id); }

    public boolean delete(String id) { return analyses.remove(id) != null; }

    public int count() { return analyses.size(); }
}
