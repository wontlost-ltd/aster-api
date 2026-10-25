# Performance benchmarks

Raw wrk output from local podman load tests. Each file is the
`tee`'d output of a sweep harness — keep them as artifacts so future
regressions can be compared against a known baseline.

## Quick index

| File | Date | What it measures |
|---|---|---|
| `loadtest-results.txt` | 2026-05-24 | First sweep — found GraalVM Engine-per-request OOM at c≥4. Before/after numbers for `fix(eval): share GraalVM Engine`. |
| `bench-results.txt` | 2026-05-24 | 3 container sizes × 2 endpoints × 5 concurrencies. Source for the "Medium is sweet spot" recommendation. |
| `k8s-bench-results.txt` | 2026-05-24 | K8s-recommended spec (2 CPU / 1 GB heap), 10 min sustained at c=16 and c=64. Source for the 5000万次/天 daily throughput estimate. |
| `prod-loadtest-20261026.md` | 2026-10-26 | **生产环境**全链路压测（Cloudflare → Tunnel → Traefik → aster-api）：evaluate 稳定 ≈50 RPS、匿名读 ≈150 RPS，瓶颈分析、并发用户估算与按优先级的扩展方案（中文）。 |

## Headline numbers — `/api/v1/policies/evaluate` (DB-backed, prod path)

K8s spec (2 CPU / 1 GB heap), 10-min sustained:

| Concurrency | RPS | p50 | p99 | Errors | Mem drift |
|---|---:|---:|---:|---:|---|
| c=16 (typical) | 4137 | 3.6 ms | 12.5 ms | 0 | 657 → 665 MB |
| c=64 (peak) | 4375 | 13.9 ms | 43.3 ms | 0 | 672 → 689 MB |

**Daily throughput**: ~50 M evaluations / day / pod (assuming peak = 5× daily-average; 70% safe-zone utilization).

## Headline numbers — `/api/v1/policies/evaluate-source` (dashboard preview, gated)

Same K8s spec, 2-min stress at c=8 (intentionally over the cap=4):

| Metric | Value | Interpretation |
|---|---:|---|
| RPS | 142 | Throughput at the semaphore-limited ceiling |
| Success rate | 99.55% | 76 / 17067 non-2xx; 54 of those are intentional 503s from the bounded-concurrency gate |
| OOM count | 55 | Tail of straggler requests queued in the worker pool — see "Known caveat" below |

## Known caveat (`/evaluate-source` at heavy overload)

The static `Semaphore` caps in-flight Polyglot work at 4 (= min(2×CPU, heap/64MB)). But Quarkus's worker pool defaults to `max-threads=200`, so requests above the semaphore line queue in the worker pool — each one still owns a Uni-lambda's stack frame and a captured `RoutingContext`. Long-running overload eventually OOMs from the *queue*, not from the in-flight work.

Mitigation (when production warrants it):
```properties
quarkus.thread-pool.max-threads=16
```

Not blocking for production: `/evaluate-source` is internal-only, gated by `InternalCallerFilter` + traefik `block-evaluate-source`. Real public traffic goes through `/evaluate` (which uses a pre-warmed Context pool and has no scaling problem).

## How to re-run

The harness scripts that produced these files lived in `/tmp/` during the original session — they are intentionally not committed so future runs are free to evolve. A re-run looks like:

```bash
# 1. Build a JVM image
./gradlew quarkusBuild -x test
podman build -f Dockerfile.jvm -t aster/policy-api:bench .

# 2. Stand up pg + redis + api with K8s-equivalent limits
podman network create bench-net
podman run -d --name bench-pg --network bench-net \
  -e POSTGRES_USER=aster -e POSTGRES_PASSWORD=aster -e POSTGRES_DB=aster_policy \
  postgres:17-alpine
podman run -d --name bench-redis --network bench-net redis:7-alpine
podman run -d --name bench-api --network bench-net -p 48080:8080 \
  --cpus 2 --memory 2g \
  -e QUARKUS_DATASOURCE_USERNAME=aster -e QUARKUS_DATASOURCE_PASSWORD=aster \
  -e QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://bench-pg:5432/aster_policy \
  -e QUARKUS_DATASOURCE_REACTIVE_URL=postgresql://bench-pg:5432/aster_policy \
  -e QUARKUS_REDIS_HOSTS=redis://bench-redis:6379 \
  -e ASTER_SECURITY_SIGNATURE_ENABLED=false \
  -e ASTER_SECURITY_APIKEY_ENABLED=false \
  -e ASTER_PLAN_GATE_ENABLED=false \
  -e ASTER_RATELIMIT_ENABLED=false \
  -e QUARKUS_OTEL_SDK_DISABLED=true \
  -e JAVA_OPTS="-Xmx1g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=100" \
  aster/policy-api:bench

# 3. Seed a trivial policy + catalog (see seed snippets in k8s-bench-results.txt header)
# 4. Drive load
wrk -t4 -c64 -d10m --timeout 5s -s wrk_db.lua --latency \
  http://localhost:48080/api/v1/policies/evaluate
```

Look at the existing files for the exact wrk Lua scripts, headers, and JSON bodies that were used.
