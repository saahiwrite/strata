package edu.neu.strata.inventory.chaos;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The memory switch. While it is on, every second it allocates {@code mbPerSecond} MB and keeps a
 * reference to it, until {@code maxMb} is held. The JVM heap grows the way it would with a real
 * leak, which shows up in {@code jvm_memory_used_bytes}; with a large enough cap the service
 * eventually fails with OutOfMemoryError. Reset drops the references so GC can reclaim them.
 */
@Component
public class MemoryLeak {

    public static final int MAX_MB_PER_SECOND = 256;
    public static final int MAX_CAP_MB = 16_384;
    private static final int MB = 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(MemoryLeak.class);

    private final List<byte[]> retained = new ArrayList<>();
    private int mbPerSecond;
    private int maxMb;

    public MemoryLeak(MeterRegistry registry) {
        Gauge.builder("chaos_memory_leaked_bytes", this, MemoryLeak::leakedBytes)
                .description("Heap the memory switch is currently holding")
                .baseUnit("bytes")
                .register(registry);
    }

    public synchronized void start(int mbPerSecond, int maxMb) {
        if (mbPerSecond <= 0 || mbPerSecond > MAX_MB_PER_SECOND) {
            throw new IllegalArgumentException("mbPerSecond must be between 1 and " + MAX_MB_PER_SECOND);
        }
        if (maxMb <= 0 || maxMb > MAX_CAP_MB) {
            throw new IllegalArgumentException("maxMb must be between 1 and " + MAX_CAP_MB);
        }
        this.mbPerSecond = mbPerSecond;
        this.maxMb = maxMb;
    }

    public synchronized void stop() {
        mbPerSecond = 0;
        maxMb = 0;
        retained.clear();
    }

    @Scheduled(fixedRate = 1000)
    public synchronized void tick() {
        if (mbPerSecond == 0) {
            return;
        }
        int toAllocate = Math.min(mbPerSecond, maxMb - retained.size());
        for (int i = 0; i < toAllocate; i++) {
            byte[] chunk = new byte[MB];
            // Touch every page so the memory is actually committed, not just reserved.
            for (int j = 0; j < chunk.length; j += 4096) {
                chunk[j] = 1;
            }
            retained.add(chunk);
        }
        if (toAllocate > 0) {
            log.warn("Memory growth: holding leakedMb={} of maxMb={}", retained.size(), maxMb);
        }
    }

    public synchronized boolean active() {
        return mbPerSecond > 0;
    }

    public synchronized int mbPerSecond() {
        return mbPerSecond;
    }

    public synchronized int maxMb() {
        return maxMb;
    }

    public synchronized long leakedBytes() {
        return (long) retained.size() * MB;
    }
}
