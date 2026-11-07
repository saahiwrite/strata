package edu.neu.strata.order.chaos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Failure switches used to inject the scenarios STRATA has to heal.
 *
 * <pre>
 * GET  /chaos                    current switch settings
 * POST /chaos/crash              exit the process with code 1
 * POST /chaos/error?rate=0.5     fail that fraction of requests with HTTP 500
 * POST /chaos/slow?delayMs=2000  add that much latency to every request
 * POST /chaos/memory?mbPerSecond=10&maxMb=512
 *                                retain that much more heap every second, up to maxMb
 * POST /chaos/reset              turn every switch off
 * </pre>
 */
@RestController
@RequestMapping("/chaos")
public class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);

    private final ChaosState state;
    private final MemoryLeak memoryLeak;
    private final Terminator terminator;

    public ChaosController(ChaosState state, MemoryLeak memoryLeak, Terminator terminator) {
        this.state = state;
        this.memoryLeak = memoryLeak;
        this.terminator = terminator;
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorRate", state.errorRate());
        body.put("latencyMs", state.latencyMs());
        body.put("memory", Map.of(
                "active", memoryLeak.active(),
                "mbPerSecond", memoryLeak.mbPerSecond(),
                "maxMb", memoryLeak.maxMb(),
                "leakedMb", memoryLeak.leakedBytes() / (1024 * 1024)));
        return body;
    }

    @PostMapping("/crash")
    public Map<String, Object> crash() {
        log.error("Fatal error: process crashing on chaos request, exitCode=1");
        terminator.terminate(1);
        return Map.of("switch", "crash", "status", "terminating");
    }

    @PostMapping("/error")
    public Map<String, Object> error(@RequestParam(defaultValue = "1.0") double rate) {
        state.setErrorRate(rate);
        log.warn("Chaos switch set: error rate={}", rate);
        return status();
    }

    @PostMapping("/slow")
    public Map<String, Object> slow(@RequestParam(defaultValue = "2000") long delayMs) {
        state.setLatencyMs(delayMs);
        log.warn("Chaos switch set: slow delayMs={}", delayMs);
        return status();
    }

    @PostMapping("/memory")
    public Map<String, Object> memory(@RequestParam(defaultValue = "10") int mbPerSecond,
                                      @RequestParam(defaultValue = "512") int maxMb) {
        memoryLeak.start(mbPerSecond, maxMb);
        log.warn("Chaos switch set: memory growth mbPerSecond={} maxMb={}", mbPerSecond, maxMb);
        return status();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        state.reset();
        memoryLeak.stop();
        log.info("Chaos switches reset");
        return status();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
