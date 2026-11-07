package edu.neu.strata.healer.verification;

import java.time.Duration;

/** Checks the metrics after an action to see whether the service recovered (FR9). */
public interface RecoveryVerifier {

    /** True when {@code service} is healthy again within {@code timeout}. */
    boolean verify(String service, Duration timeout);
}
