package edu.neu.strata.inventory.chaos;

/** Ends the process. Separated out so tests can verify a crash without killing the JVM. */
@FunctionalInterface
public interface Terminator {

    void terminate(int exitCode);
}
