package edu.neu.strata.healer.verification;

/** Prometheus could not answer a query (unreachable, query error or unexpected result). */
public class PrometheusException extends RuntimeException {

    public PrometheusException(String message) {
        super(message);
    }

    public PrometheusException(String message, Throwable cause) {
        super(message, cause);
    }
}
