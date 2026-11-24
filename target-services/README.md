# Target Services

The two Spring Boot services that STRATA watches and heals. Both expose Prometheus metrics, log JSON to stdout and have the same failure switches (see [`scenarios/README.md`](../scenarios/README.md#failure-switches)).

The switches live once, in `chaos-kit`: a plain library jar with a Spring Boot auto-configuration (`ChaosAutoConfiguration`). A service gets `/chaos/*`, the filter and the switch metrics just by depending on it. Set `strata.chaos.enabled=false` to leave them out.

| Service | Port (in container) | API | Compose host name |
|---|---|---|---|
| `order-service` | 8080 | `GET /orders`, `GET /orders/{id}`, `POST /orders` (`{"sku":"SKU-1","quantity":2}`) | `order-service` |
| `inventory-service` | 8080 | `GET /inventory`, `GET /inventory/{sku}`, `POST /inventory/{sku}/reserve?quantity=1` | `inventory-service` |
| Nginx (`infra/nginx/nginx.conf`) | 8080 | `/orders`, `/inventory`, plus `/order-service/*` and `/inventory-service/*` for `/chaos` and `/actuator` | `nginx` |

Every service has `/actuator/health` (with liveness and readiness groups) and `/actuator/prometheus`. All metrics carry an `application` tag with the service name.

## Build and run one service

```bash
mvn verify -pl target-services/order-service -am   # from the repo root: builds chaos-kit first, then the service
java -jar target-services/order-service/target/order-service.jar   # listens on 8080
```

Run Maven from the repo root (or `mvn install` chaos-kit once): a service folder on its own can't find chaos-kit.

Both services listen on 8080 by default, which is what Prometheus and Nginx expect inside Compose. To run both on one machine outside Docker, give the second one another port:

```bash
SERVER_PORT=8082 java -jar target/inventory-service.jar
```

Add `--spring.profiles.active=plain-logs` for human-readable logs instead of JSON.

Each service has a `Dockerfile` that copies `target/<service>.jar`, so build the jar before `docker build`. The image runs with `-XX:+ExitOnOutOfMemoryError`, so the memory switch ends in a real crash once the heap is full.

## Releases (order-service)

`order-service` reports its release in `service_release{version,broken}`. A broken release (`strata.release.broken=true`, baked into the `1.1-broken` image) fails most business requests, for the bad-deployment scenario S14. See [`scenarios/README.md`](../scenarios/README.md#bad-deployment-s14).

## Log format

One JSON object per line, for example:

```json
{"timestamp":"2026-11-07T03:12:08.709Z","message":"Request failed: injected error path=/orders method=GET","logger_name":"edu.neu.strata.chaos.ChaosFilter","thread_name":"http-nio-8080-exec-5","level":"ERROR","stack_trace":"java.lang.IllegalStateException: Chaos error switch is on\n...","service":"order-service"}
```
