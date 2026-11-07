package edu.neu.strata.order.api;

import java.time.Instant;

public record Order(long id, String sku, int quantity, Instant createdAt) {
}
