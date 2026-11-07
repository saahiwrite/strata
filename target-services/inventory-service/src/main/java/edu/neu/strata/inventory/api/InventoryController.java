package edu.neu.strata.inventory.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal in-memory stock API, seeded with a few SKUs. Like order-service, it exists to give
 * STRATA a second service with real traffic, metrics and logs.
 */
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    static final int INITIAL_STOCK = 100;

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    private final Map<String, Integer> stock = new ConcurrentHashMap<>();
    private final Counter reservations;
    private final Counter rejectedReservations;

    public InventoryController(MeterRegistry registry) {
        for (int i = 1; i <= 5; i++) {
            stock.put("SKU-" + i, INITIAL_STOCK);
        }
        this.reservations = Counter.builder("inventory_reservations")
                .description("Successful stock reservations")
                .register(registry);
        this.rejectedReservations = Counter.builder("inventory_reservations_rejected")
                .description("Reservations rejected for insufficient stock")
                .register(registry);
    }

    @GetMapping
    public List<StockItem> list() {
        return stock.entrySet().stream()
                .map(e -> new StockItem(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(StockItem::sku))
                .toList();
    }

    @GetMapping("/{sku}")
    public ResponseEntity<StockItem> get(@PathVariable String sku) {
        Integer available = stock.get(sku);
        return available == null ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(new StockItem(sku, available));
    }

    @PostMapping("/{sku}/reserve")
    public ResponseEntity<?> reserve(@PathVariable String sku, @RequestParam(defaultValue = "1") int quantity) {
        if (quantity <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "quantity must be positive"));
        }
        if (!stock.containsKey(sku)) {
            return ResponseEntity.notFound().build();
        }
        int[] remaining = {-1};
        stock.computeIfPresent(sku, (key, available) -> {
            if (available < quantity) {
                return available;
            }
            remaining[0] = available - quantity;
            return remaining[0];
        });
        if (remaining[0] < 0) {
            rejectedReservations.increment();
            log.warn("Reservation rejected sku={} quantity={} reason=insufficient_stock", sku, quantity);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "insufficient stock", "sku", sku));
        }
        reservations.increment();
        log.info("Stock reserved sku={} quantity={} remaining={}", sku, quantity, remaining[0]);
        return ResponseEntity.ok(new StockItem(sku, remaining[0]));
    }
}
