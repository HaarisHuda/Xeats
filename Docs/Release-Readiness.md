# Backend release readiness — Phase 6.1

**Status:** Local release gates passed on 2026-10-20. The service is ready for the user to push to GitHub. GitHub Actions has not run for that push yet. Hosted deployment and HTTPS smoke checks are Phase 6.2 work and have not been performed.

## Commands and evidence

The verification ran on Windows with Java 21.0.12.1, the Maven 3.9.16 distribution used by the wrapper, Docker Desktop engine 29.5.3, and a fresh Testcontainers MySQL 8.4.11 container.

```powershell
$env:JAVA_HOME='D:\Xeats\.tools\jdks\jdk-21.0.12.1+1'
& 'D:\Xeats\.tools\maven\apache-maven-3.9.16\bin\mvn.cmd' '-Dmaven.repo.local=D:/Xeats/.tools/m2' verify
python scripts/validate_backend_contract.py
python ..\scripts\validate_backend_contract.py
```

`mvn verify` completed successfully with 30 tests: 15 HTTP/MySQL integration tests and 15 unit tests. Testcontainers applied all seven Flyway migrations to a newly created database. The admission race test submitted 24 simultaneous orders from distinct employee sessions for a slot with one remaining online position: exactly one order was accepted, 23 received `409 SLOT_UNAVAILABLE`, and the slot allocation increased once. A separate same-employee race with different idempotency keys produced one accepted order and one `409 ACTIVE_ORDER_EXISTS`. This is a bounded concurrency regression check, not a sustained throughput or production capacity benchmark.

Both contract validator runs passed: OpenAPI 3.1, 33 paths, 33 operations, 46 schemas, 240 local references, templated parameters, business invariants, idempotency/precondition headers, ETA arithmetic, and the SSE example.

The ordered HTTP/MySQL suite covers clean migrations and demo seeding; session login, CSRF, discovery, options, order creation, employee history; same-key replay and changed-payload conflict; final-slot and sold-out races; two-counter independent orders; owner and assigned-counter restrictions; manual expiry; versioned fulfillment; token redemption and expiry races; live-event privacy, commit/rollback timing, disconnect and snapshot refetch; timezone report dates and snapshot stability; and readiness failure when MySQL is stopped. Public health responses include sanitized component status but omit the JDBC URL, schema name, and credentials. Unexpected API failures return a generic problem detail; request bodies, headers, and pickup codes are not added to application logs.

CI in `.github/workflows/ci.yml` runs Maven verification against disposable MySQL and runs the same OpenAPI/business-contract validator. The workflow definition is locally reviewed but will receive its first GitHub-hosted run after the push.

## Client handoff

- `openapi.yaml` is the authoritative typed HTTP contract for both employee and vendor apps.
- `API-Examples.md` gives representative response and request payloads for employee discovery/order, counter queue/fulfillment, collection, reports, SSE, and problem details.
- `../scripts/employee-checkout.http` and `../scripts/staff-fulfillment.http` provide an executable REST Client handoff journey for the synthetic demo profile. Login refreshes CSRF tokens before mutations; copy only the order ID and pickup code from the owner's order response to the staff request variables.
- Demo HTTP scripts require an open, available counter with online ordering enabled. Use synthetic demo accounts and an isolated demo database only.

## Known limitations

- Inventory is a staff-controlled available/sold-out toggle; unit counts and automatic stock depletion are not measured.
- Orders never expire automatically; assigned staff must expire overdue/unfulfillable orders manually.
- Readiness is an approximate workload projection based on configured service assumptions. Physical onsite wait remains null because walk-in arrival and handover times are unmeasured.
- Walk-in capacity is reserved in scheduling but actual walk-ins are not counted.
- Sessions, login/redemption throttles, and live-event delivery are single-instance in-memory behavior; the deployment must run a single backend instance until distributed replacements are designed.
- SSE provides committed notifications and heartbeats but no replay. Clients reconnect and refetch the authoritative authorized snapshots.
- Order values are simulated and do not represent payment, settlement, or revenue.

## Deferred to Phase 6.2

No hosting provider, production secrets, TLS/reverse proxy, hosted database, backup schedule, or deployment pipeline has been provisioned. After selecting an independent demo environment, run production-profile HTTPS readiness, migration/seed, employee-to-staff-to-employee smoke journey, duplicate redemption, SSE through the proxy, and restart/reconnect checks; capture the actual API base URL and release identifier. Do not use production/shared data for the demo fixture reset.
