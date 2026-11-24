package edu.neu.strata.order.release;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which release of order-service is running. The values are baked into the Docker image at
 * build time (see the Dockerfile), not passed as environment variables: STRATA's rollback copies
 * a container's environment to the replacement container, so an environment flag would survive
 * a rollback, while a file in the image is replaced along with the image.
 *
 * @param version     release label, e.g. {@code 1.0} or {@code 1.1-broken}
 * @param broken      whether this release has the injected bug (scenario S14)
 * @param failureRate fraction of business requests the broken release fails
 */
@ConfigurationProperties(prefix = "strata.release")
public record ReleaseProperties(String version, boolean broken, Double failureRate) {

    public ReleaseProperties {
        version = version == null || version.isBlank() ? "dev" : version;
        failureRate = failureRate == null ? 0.9 : failureRate;
        if (failureRate < 0 || failureRate > 1) {
            throw new IllegalArgumentException("strata.release.failure-rate must be between 0 and 1");
        }
    }
}
