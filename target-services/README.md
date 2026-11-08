# Target Services

The two Spring Boot services that STRATA watches and heals. Both expose Prometheus metrics, log JSON to stdout and have the same failure switches (see [`scenarios/README.md`](../scenarios/README.md#failure-switches)).

| Service | Port | API | Compose host name |
|---|---|---|---|
| `order-service` | 8081 | `GET /orders`, `GET /orders/{id}`, `POST /orders` (`{"sku":"SKU-1","quantity":2}`) | `order-service` |
| `inventory-service` | 8082 | `GET /inventory`, `GET /inventory/{sku}`, `POST /inventory/{sku}/reserve?quantity=1` | `inventory-service` |
| Nginx (`infra/nginx/nginx.conf`) | 8080 | `/orders`, `/inventory`, plus `/order-service/*` and `/inventory-service/*` for `/chaos` and `/actuator` | `nginx` |

Every service has `/actuator/health` (with liveness and readiness groups) and `/actuator/prometheus`. All metrics carry an `application` tag with the service name.

## Build and run one service

```bash
cd target-services/order-service
mvn verify                       # build and run the tests
java -jar target/order-service.jar
```

Add `--spring.profiles.active=plain-logs` for human-readable logs instead of JSON.

Each service has a `Dockerfile` that copies `target/<service>.jar`, so build the jar before `docker build`. The image runs with `-XX:+ExitOnOutOfMemoryError`, so the memory switch ends in a real crash once the heap is full.

## Log format

One JSON object per line, for example:

```json
{"timestamp":"2026-11-07T03:12:08.709Z","message":"Request failed: injected error path=/orders method=GET","logger_name":"edu.neu.strata.order.chaos.ChaosFilter","thread_name":"http-nio-8081-exec-5","level":"ERROR","stack_trace":"java.lang.IllegalStateException: Chaos error switch is on\n...","service":"order-service"}
```
