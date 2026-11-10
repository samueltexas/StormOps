package com.stormops.api.api;

/** Parameters are validated by the experiment service before any HTTP request. */
public record HttpSimulationRequest(String type, Integer latencyMillis) {
}
