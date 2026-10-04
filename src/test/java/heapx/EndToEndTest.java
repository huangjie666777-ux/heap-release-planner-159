package heapx;

import heapx.http.Api;
import heapx.model.HeapModel;
import heapx.parse.HprofParser;
import heapx.service.Analysis;
import heapx.service.AnalysisService;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EndToEndTest {

    /** Builds a real HPROF file exercising fields, arrays, statics, roots,
     *  Reference.referent exclusion and unreachable objects. */
    static Path buildDump(Path dir) throws Exception {
        HprofWriter w = new HprofWriter();
        w.defineClass(100, "demo.Base", 0)
                .field("ref", HprofWriter.T_OBJECT)
                .field("val", HprofWriter.T_INT);
        w.defineClass(101, "demo.Sub", 100)
                .field("arr", HprofWriter.T_OBJECT);
        w.defineClass(102, "demo.Holder", 0)
                .staticRef("cache", 1);
        w.defineClass(103, "java.lang.ref.Reference", 0)
                .field("referent", HprofWriter.T_OBJECT);
        w.defineClass(104, "demo.Ref", 103);
        w.defineClass(105, "demo.ObjArray", 0);
        w.defineClass(106, "demo.Leaf", 0);
        w.defineClass(110, "byte[]", 0); // required by the reader for primitive arrays

        w.instance(1, 101, 5, 2, 42); // own-class fields first: arr, ref, val       // static root via Holder.cache
        w.instance(2, 100, 3, 1);
        w.instance(3, 106);
        w.instance(4, 104, 11);             // JNI root; referent=11 must be ignored
        w.objectArray(5, 105, 2, 6, 7);
        w.instance(6, 106);
        w.byteArray(7, new byte[1000]);
        w.instance(8, 100, 9, 0);           // unreachable chain
        w.instance(9, 106);
        w.instance(10, 106);                // JNI root
        w.instance(11, 106);                // only referenced via referent -> unreachable
        w.jniGlobalRoot(4);
        w.jniGlobalRoot(10);
        return w.write(dir.resolve("test.hprof"));
    }

    @Test
    void parseAndAnalyzeDump() throws Exception {
        Path dump = buildDump(Files.createTempDirectory("heapx-test"));
        AnalysisService svc = new AnalysisService();
        Analysis a;
        try (var in = Files.newInputStream(dump)) {
            a = svc.analyze(in);
        }
        HeapModel g = a.model;
        assertEquals(11, g.nodeCount());
        // referent edge excluded: object 11 unreachable; 8,9 unreachable too
        assertEquals(3, countUnreachable(a));
        Integer obj1 = g.indexOf(1);
        Integer obj2 = g.indexOf(2);
        Integer obj5 = g.indexOf(5);
        assertNotNull(obj1);
        assertTrue(g.root[obj1]);
        assertTrue(g.root[g.indexOf(4)]);
        assertTrue(g.root[g.indexOf(10)]);
        // retained[1] = subtree {1,2,3,5,6,7}
        long expected = g.shallow[obj1] + g.shallow[obj2] + g.shallow[g.indexOf(3)]
                + g.shallow[obj5] + g.shallow[g.indexOf(6)] + g.shallow[g.indexOf(7)];
        assertEquals(expected, a.dominators.retained[obj1]);
        assertEquals(g.shallow[obj2] + g.shallow[g.indexOf(3)], a.dominators.retained[obj2]);
        assertEquals(obj1, a.dominators.idom[obj2]);
        assertEquals(obj1, a.dominators.idom[obj5]);
        svc.delete(a.id);
        assertNull(svc.get(a.id));
    }

    private static int countUnreachable(Analysis a) {
        int c = 0;
        for (boolean u : a.dominators.unreachable) if (u) c++;
        return c;
    }

    @Test
    void httpUploadRankPathDelete() throws Exception {
        Path dump = buildDump(Files.createTempDirectory("heapx-http"));
        Javalin app = Api.create(new AnalysisService(), 0);
        String base = "http://localhost:" + app.port();
        HttpClient http = HttpClient.newHttpClient();
        try {
            // upload
            HttpResponse<String> up = http.send(HttpRequest.newBuilder(URI.create(base + "/api/analyses"))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofFile(dump)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, up.statusCode(), up.body());
            String analysisId = up.body().replaceAll(".*\"analysisId\"\s*:\s*\"([^\"]+)\".*", "$1");

            // retained ranking
            HttpResponse<String> rank = http.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/analyses/" + analysisId + "/retained?limit=5")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, rank.statusCode());
            assertTrue(rank.body().contains("\"unreachableObjects\":3"), rank.body());
            // 0x1 retains the whole reachable subgraph incl. the byte[1000]
            assertTrue(rank.body().indexOf("\"id\":\"0x1\"")
                    < rank.body().indexOf("\"id\":\"0x7\""), rank.body());
            assertTrue(rank.body().contains("\"retainedBytes\":1096"), rank.body());

            // object detail with immediate dominator
            HttpResponse<String> obj = http.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/analyses/" + analysisId + "/objects/0x3")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, obj.statusCode());
            assertTrue(obj.body().contains("\"immediateDominator\":\"0x2\""), obj.body());

            // shortest root path for object 3: 1 -> 2 -> 3 via demo.Base#ref
            HttpResponse<String> path = http.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/analyses/" + analysisId + "/objects/0x3/path")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, path.statusCode(), path.body());
            assertTrue(path.body().contains("demo.Base#ref"), path.body());
            assertTrue(path.body().contains("\"pathLength\":2"), path.body());

            // unreachable object 11 -> 422
            HttpResponse<String> dead = http.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/analyses/" + analysisId + "/objects/0xb/path")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(422, dead.statusCode(), dead.body());

            // corrupt upload rejected, nothing published
            HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(base + "/api/analyses"))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3})).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, bad.statusCode());

            // delete releases the analysis
            HttpResponse<String> del = http.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/analyses/" + analysisId)).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, del.statusCode());
            HttpResponse<String> gone = http.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/analyses/" + analysisId)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, gone.statusCode());
        } finally {
            app.stop();
        }
    }

    @Test
    void parserRejectsOverLimit() throws Exception {
        Path dump = buildDump(Files.createTempDirectory("heapx-limit"));
        HprofParser tiny = new HprofParser(3, 200000);
        try {
            tiny.parse(dump.toFile());
            fail("expected object limit rejection");
        } catch (heapx.parse.AnalysisException e) {
            assertEquals(422, e.status);
        }
        HprofParser fewEdges = new HprofParser(50000, 2);
        try {
            fewEdges.parse(dump.toFile());
            fail("expected edge limit rejection");
        } catch (heapx.parse.AnalysisException e) {
            assertEquals(422, e.status);
        }
    }

    @Test
    void httpCutPlan() throws Exception {
        Path dump = buildDump(Files.createTempDirectory("heapx-cut"));
        Javalin app = Api.create(new AnalysisService(), 0);
        String base = "http://localhost:" + app.port();
        HttpClient http = HttpClient.newHttpClient();
        try {
            HttpResponse<String> up = http.send(HttpRequest.newBuilder(URI.create(base + "/api/analyses"))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofFile(dump)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, up.statusCode(), up.body());
            String id = up.body().replaceAll(".*\"analysisId\"\s*:\s*\"([^\"]+)\".*", "$1");
            String url = base + "/api/analyses/" + id + "/cut-plan";

            // target 0x7 (byte[1000]): cut [2] for 3 instead of demo.Sub#arr for 10
            String req = """
                    {"targets":["0x7"],"candidates":[
                      {"source":"0x1","via":"demo.Sub#arr","target":"0x5","cost":10},
                      {"source":"0x5","via":"[2]","target":"0x7","cost":3}]}
                    """;
            HttpResponse<String> plan = post(http, url, req);
            assertEquals(200, plan.statusCode(), plan.body());
            assertTrue(plan.body().contains("\"feasible\":true"), plan.body());
            assertTrue(plan.body().contains("\"totalCost\":3"), plan.body());
            assertTrue(plan.body().contains("\"via\":\"[2]\""), plan.body());
            assertFalse(plan.body().contains("demo.Sub#arr\",\"target"), plan.body());
            // newly unreachable: exactly object 0x7 with its shallow bytes
            assertTrue(plan.body().contains("\"count\":1"), plan.body());
            assertTrue(plan.body().contains("0x7"), plan.body());
            assertFalse(plan.body().contains("0x8"), plan.body()); // pre-unreachable not counted

            // two targets sharing the prefix: cutting 0x1->0x5 (10) loses to [1]+[2] (4+3)
            String req2 = """
                    {"targets":["0x6","0x7"],"candidates":[
                      {"source":"0x1","via":"demo.Sub#arr","target":"0x5","cost":10},
                      {"source":"0x5","via":"[1]","target":"0x6","cost":4},
                      {"source":"0x5","via":"[2]","target":"0x7","cost":3}]}
                    """;
            HttpResponse<String> plan2 = post(http, url, req2);
            assertEquals(200, plan2.statusCode(), plan2.body());
            assertTrue(plan2.body().contains("\"totalCost\":7"), plan2.body());
            assertTrue(plan2.body().contains("\"count\":2"), plan2.body());

            // already unreachable target: free
            HttpResponse<String> free = post(http, url, "{\"targets\":[\"0x9\"],\"candidates\":[]}");
            assertEquals(200, free.statusCode(), free.body());
            assertTrue(free.body().contains("\"totalCost\":0"), free.body());
            assertTrue(free.body().contains("\"alreadyUnreachableTargets\":[\"0x9\"]"), free.body());

            // infeasible: 0x2->0x3 not cuttable -> evidence path over uncuttable edges
            String reqInf = """
                    {"targets":["0x3"],"candidates":[
                      {"source":"0x1","via":"demo.Base#ref","target":"0x2","cost":5}]}
                    """;
            HttpResponse<String> inf = post(http, url, reqInf);
            assertEquals(422, inf.statusCode(), inf.body());
            assertTrue(inf.body().contains("\"feasible\":false"), inf.body());
            assertTrue(inf.body().contains("demo.Base#ref"), inf.body());

            // root as target -> explained
            HttpResponse<String> rootT = post(http, url, "{\"targets\":[\"0xa\"],\"candidates\":[]}");
            assertEquals(422, rootT.statusCode(), rootT.body());
            assertTrue(rootT.body().contains("\"targetIsRoot\":true"), rootT.body());

            // validation failures
            assertEquals(400, post(http, url,
                    "{\"targets\":[\"0x7\"],\"candidates\":[{\"source\":\"0x5\",\"via\":\"[2]\",\"target\":\"0x7\",\"cost\":0}]}").statusCode());
            assertEquals(400, post(http, url,
                    "{\"targets\":[\"0x7\"],\"candidates\":[{\"source\":\"0x5\",\"via\":\"[2]\",\"target\":\"0x7\",\"cost\":1000000001}]}").statusCode());
            String dup = """
                    {"targets":["0x7"],"candidates":[
                      {"source":"0x5","via":"[2]","target":"0x7","cost":1},
                      {"source":"0x5","via":"[2]","target":"0x7","cost":2}]}
                    """;
            assertEquals(400, post(http, url, dup).statusCode());
            assertEquals(400, post(http, url,
                    "{\"targets\":[\"0x7\"],\"candidates\":[{\"source\":\"0x5\",\"via\":\"[9]\",\"target\":\"0x7\",\"cost\":1}]}").statusCode());
            assertEquals(400, post(http, url,
                    "{\"targets\":[\"0xdead\"],\"candidates\":[]}").statusCode());

            // planning is read-only: ranking unchanged afterwards
            HttpResponse<String> rank = http.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/analyses/" + id + "/retained?limit=5")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, rank.statusCode());
            assertTrue(rank.body().contains("\"retainedBytes\":1096"), rank.body());

            // after delete, planning is gone too
            http.send(HttpRequest.newBuilder(URI.create(base + "/api/analyses/" + id)).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, post(http, url, "{\"targets\":[\"0x7\"],\"candidates\":[]}").statusCode());
        } finally {
            app.stop();
        }
    }

    private static HttpResponse<String> post(HttpClient http, String url, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
