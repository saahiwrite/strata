# Two stages, so the bad-deployment scenario (S14) can build a second tag from the same jar.
#
#   release  the normal image. Compose builds this one as strata/order-service:1.0.
#   upgrade  a release that was deployed on top of another image; it records that image in the
#            strata.previous-image label, which is what STRATA's ROLLBACK action goes back to.
#            Built by `scenarios/inject.sh deploy order-service 1.1-broken`.
#
# The release flags go into /app/release.properties, not into ENV: STRATA's rollback copies
# a container's environment to the replacement, so an ENV flag would survive the rollback.

FROM eclipse-temurin:21-jre AS release
ARG RELEASE_VERSION=1.0
ARG BROKEN_RELEASE=false
WORKDIR /app
COPY target/order-service.jar app.jar
RUN printf 'strata.release.version=%s\nstrata.release.broken=%s\n' "$RELEASE_VERSION" "$BROKEN_RELEASE" \
        > /app/release.properties
LABEL strata.release=$RELEASE_VERSION
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar", \
            "--spring.config.additional-location=optional:file:/app/release.properties"]

FROM release AS upgrade
ARG PREVIOUS_IMAGE
LABEL strata.previous-image=$PREVIOUS_IMAGE
