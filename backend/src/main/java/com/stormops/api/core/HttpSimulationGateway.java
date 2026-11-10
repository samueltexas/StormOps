package com.stormops.api.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Calls only the local StormOps demo tunnel; arbitrary URLs are never accepted. */
@Component
public class HttpSimulationGateway {

    private static final URI DEMO_BASE = URI.create("http://127.0.0.1:18081");
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public Observation invoke(String type, int latencyMillis) {
        String path = switch (type) {
            case "BASELINE" -> "/api/simulations/baseline";
            case "HTTP_LATENCY" -> "/api/simulations/latency?milliseconds=" + latencyMillis;
            case "HTTP_ERROR" -> "/api/simulations/error";
            default -> throw new IllegalArgumentException("Unsupported HTTP experiment type");
        };

        HttpRequest request = HttpRequest.newBuilder(DEMO_BASE.resolve(path))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();

        long started = System.nanoTime();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return new Observation(response.statusCode(), elapsed, response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP simulation interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("StormOps demo tunnel is unavailable on 127.0.0.1:18081", exception);
        }
    }

    public record Observation(int statusCode, long elapsedMillis, String body) {
    }
}
