package edu.neu.strata.order.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal in-memory order API. It exists so that STRATA has real traffic, latency and
 * error-rate metrics to watch; persistence is intentionally out of scope.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final Map<Long, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final Counter ordersPlaced;

    public OrderController(MeterRegistry registry) {
        this.ordersPlaced = Counter.builder("orders_placed")
                .description("Number of orders created")
                .register(registry);
    }

    @GetMapping
    public List<Order> list() {
        return orders.values().stream().sorted((a, b) -> Long.compare(a.id(), b.id())).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> get(@PathVariable long id) {
        Order order = orders.get(id);
        return order == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(order);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody OrderRequest request) {
        if (request.sku() == null || request.sku().isBlank() || request.quantity() <= 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "sku is required and quantity must be positive"));
        }
        Order order = new Order(nextId.getAndIncrement(), request.sku(), request.quantity(), Instant.now());
        orders.put(order.id(), order);
        ordersPlaced.increment();
        log.info("Order created id={} sku={} quantity={}", order.id(), order.sku(), order.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }
}
