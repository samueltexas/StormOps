package com.stormops.api.core;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.stereotype.Component;

/** Fixed local telemetry target; missing, stale or malformed evidence fails closed. */
@Component
public class PrometheusGuard {
    private final RestClient client;
    public PrometheusGuard() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = RestClient.builder().requestFactory(factory).build();
    }
    public void verifyHealthy() {
        // Check scrape freshness explicitly: an instant expression's timestamp is evaluation time.
        double age = query("time() - min(timestamp(up{job=\"stormops-demo\"}))");
        double up = query("min(up{job=\"stormops-demo\"})");
        if (age < 0 || age > 45 || up != 1) throw new IllegalStateException("Telemetry is stale or target is unavailable");
        double traffic = query("sum(rate(http_server_requests_seconds_count{job=\"stormops-demo\",uri!~\"/actuator.*\"}[1m]))");
        double errors = query("sum(rate(http_server_requests_seconds_count{job=\"stormops-demo\",uri!~\"/actuator.*\",status=~\"5..\"}[1m])) or vector(0)");
        if (traffic <= 0 || errors < 0 || errors / traffic > 0.05) throw new IllegalStateException("Steady state failed: traffic required and error ratio must not exceed 5%");
    }
    double query(String query) {
        try {
            Map<?,?> body = client.get().uri(URI.create("http://127.0.0.1:19090/api/v1/query?timeout=2s&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8))).retrieve().body(Map.class);
            return value(body);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Prometheus safety evidence unavailable");
        }
    }
    static double value(Map<?,?> body) {
        try { return parseValue(body); }
        catch (RuntimeException ex) { throw new IllegalStateException("Invalid telemetry sample"); }
    }
    private static double parseValue(Map<?,?> body) {
        if (body == null || !"success".equals(body.get("status")) || body.containsKey("warnings")) throw new IllegalStateException("Invalid telemetry response");
        Map<?,?> data = (Map<?,?>) body.get("data");
        if (!"vector".equals(data.get("resultType"))) throw new IllegalStateException("Expected telemetry vector");
        List<?> rows = (List<?>) data.get("result");
        if (rows.size() != 1) throw new IllegalStateException("Expected one telemetry sample");
        List<?> sample = (List<?>) ((Map<?,?>) rows.getFirst()).get("value");
        double timestamp = Double.parseDouble(sample.getFirst().toString());
        double age = System.currentTimeMillis()/1000.0 - timestamp;
        double value = Double.parseDouble(sample.get(1).toString());
        if (!Double.isFinite(value) || !Double.isFinite(timestamp) || age < -5 || age > 15) throw new IllegalStateException("Invalid or stale telemetry sample");
        return value;
    }
}
