package heapx;

import heapx.http.Api;
import heapx.service.AnalysisService;

public final class Main {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7070"));
        Api.create(new AnalysisService(), port);
        System.out.println("heapx listening on http://localhost:" + port);
    }
}
