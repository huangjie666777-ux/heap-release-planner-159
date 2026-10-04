package heapx.http;

import heapx.graph.PathFinder;
import heapx.model.HeapModel;
import heapx.parse.AnalysisException;
import heapx.service.Analysis;
import heapx.service.AnalysisService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Javalin routes for upload, retained ranking, root paths and delete. */
public final class Api {

    public static Javalin create(AnalysisService service, int port) {
        Javalin app = Javalin.create(cfg -> {
            cfg.http.maxRequestSize = AnalysisService.MAX_BYTES + (1 << 20);
            cfg.showJavalinBanner = false;
        });

        app.post("/api/analyses", ctx -> {
            try (InputStream in = bodyOf(ctx)) {
                if (in == null) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error",
                            "provide the HPROF file as multipart field 'file' or raw request body"));
                    return;
                }
                Analysis a = service.analyze(in);
                ctx.status(HttpStatus.CREATED).json(summary(a));
            } catch (AnalysisException e) {
                ctx.status(e.status).json(Map.of("error", e.getMessage()));
            }
        });

        app.get("/api/analyses/{id}", ctx -> {
            Analysis a = require(ctx, service);
            if (a != null) ctx.json(summary(a));
        });

        app.get("/api/analyses/{id}/retained", ctx -> {
            Analysis a = require(ctx, service);
            if (a == null) return;
            int limit = Math.min(intParam(ctx, "limit", 50), 1000);
            int offset = Math.max(intParam(ctx, "offset", 0), 0);
            HeapModel g = a.model;
            int n = g.nodeCount();
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            // unreachable objects are excluded from the retained ranking
            var da = a.dominators;
            List<Integer> reachable = new ArrayList<>(n);
            for (int i = 0; i < n; i++) if (!da.unreachable[i]) reachable.add(i);
            reachable.sort((x, y) -> Long.compare(da.retained[y], da.retained[x]));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = offset; i < Math.min(offset + limit, reachable.size()); i++) {
                rows.add(objectRow(a, reachable.get(i)));
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("analysisId", a.id);
            resp.put("rankedObjects", reachable.size());
            resp.put("unreachableObjects", n - reachable.size());
            resp.put("objects", rows);
            ctx.json(resp);
        });

        app.get("/api/analyses/{id}/objects/{hexId}", ctx -> {
            Analysis a = require(ctx, service);
            if (a == null) return;
            Integer idx = objectIndex(ctx, a);
            if (idx == null) return;
            ctx.json(objectRow(a, idx));
        });

        app.get("/api/analyses/{id}/objects/{hexId}/path", ctx -> {
            Analysis a = require(ctx, service);
            if (a == null) return;
            Integer idx = objectIndex(ctx, a);
            if (idx == null) return;
            HeapModel g = a.model;
            if (a.dominators.unreachable[idx]) {
                ctx.status(HttpStatus.forStatus(422)).json(Map.of(
                        "error", "object is unreachable from GC roots",
                        "objectId", hex(g.ids[idx])));
                return;
            }
            List<PathFinder.Step> steps = PathFinder.shortestPathFromRoot(g, a.dominators, idx);
            if (steps == null) {
                ctx.status(HttpStatus.forStatus(422))
                        .json(Map.of("error", "no strong-reference path from any root"));
                return;
            }
            List<Map<String, Object>> edges = new ArrayList<>();
            for (PathFinder.Step s : steps) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("from", hex(s.fromId()));
                e.put("fromClass", s.fromClass());
                e.put("via", s.via());
                e.put("to", hex(s.toId()));
                e.put("toClass", s.toClass());
                edges.add(e);
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("analysisId", a.id);
            resp.put("objectId", hex(g.ids[idx]));
            resp.put("rootObjectId", steps.isEmpty() ? hex(g.ids[idx]) : hex(steps.get(0).fromId()));
            resp.put("isRoot", g.root[idx]);
            resp.put("pathLength", steps.size());
            resp.put("edges", edges);
            ctx.json(resp);
        });

        app.delete("/api/analyses/{id}", ctx -> {
            if (service.delete(ctx.pathParam("id"))) {
                ctx.json(Map.of("deleted", ctx.pathParam("id")));
            } else {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "unknown analysis id"));
            }
        });

        app.start(port);
        return app;
    }

    private static InputStream bodyOf(Context ctx) throws Exception {
        var uploaded = ctx.uploadedFile("file");
        if (uploaded != null) return uploaded.content();
        if (ctx.contentLength() > 0 || ctx.contentLength() == -1) return ctx.bodyInputStream();
        return null;
    }

    private static Map<String, Object> summary(Analysis a) {
        HeapModel g = a.model;
        int unreachable = 0;
        for (boolean u : a.dominators.unreachable) if (u) unreachable++;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analysisId", a.id);
        m.put("objects", g.nodeCount());
        m.put("edges", g.edgeCount());
        m.put("unreachableObjects", unreachable);
        return m;
    }

    private static Map<String, Object> objectRow(Analysis a, int idx) {
        HeapModel g = a.model;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", hex(g.ids[idx]));
        m.put("className", g.classNames[idx]);
        m.put("shallowBytes", g.shallow[idx]);
        m.put("root", g.root[idx]);
        m.put("unreachable", a.dominators.unreachable[idx]);
        if (a.dominators.unreachable[idx]) {
            m.put("retainedBytes", null);
            m.put("immediateDominator", null);
        } else {
            m.put("retainedBytes", a.dominators.retained[idx]);
            int p = a.dominators.idom[idx];
            m.put("immediateDominator",
                    p == a.dominators.virtualRoot ? "<virtual-root>" : hex(g.ids[p]));
        }
        return m;
    }

    private static Analysis require(Context ctx, AnalysisService service) {
        Analysis a = service.get(ctx.pathParam("id"));
        if (a == null) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "unknown analysis id"));
        }
        return a;
    }

    private static Integer objectIndex(Context ctx, Analysis a) {
        long id;
        try {
            id = parseHex(ctx.pathParam("hexId"));
        } catch (NumberFormatException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "invalid hex object id"));
            return null;
        }
        Integer idx = a.model.indexOf(id);
        if (idx == null) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "unknown object id"));
        }
        return idx;
    }

    static String hex(long id) { return "0x" + Long.toHexString(id); }

    static long parseHex(String s) {
        String t = s.startsWith("0x") || s.startsWith("0X") ? s.substring(2) : s;
        return Long.parseUnsignedLong(t, 16);
    }

    private static int intParam(Context ctx, String name, int dflt) {
        String v = ctx.queryParam(name);
        if (v == null) return dflt;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return dflt; }
    }
}
