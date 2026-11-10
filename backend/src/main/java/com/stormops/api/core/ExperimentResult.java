package com.stormops.api.core;

import java.time.Instant;
import java.util.UUID;

/** Immutable record of an experiment, including unsuccessful attempts. */
public record ExperimentResult(
        UUID id,
        Instant startedAt,
        Instant finishedAt,
        String type,
        String target,
        String status,
        long recoveryMillis,
        String detail
) {
}
