# Xeats backend plan

Status: Phases 0-5 and Phase 6.1 local release preparation are complete and verified against clean disposable MySQL 8.4.11. The independent hosted deployment and hosted smoke gates in Phase 6.2 remain pending.

Read alongside `Xeats.md`. The user decisions recorded below override conflicting parts of that original brief. The proposed implementation defaults are engineering choices for this prototype, not additional user requirements.

## 1. Delivery scope and sequence

Build, verify, and deploy the shared backend before developing the employee and vendor applications. Prove both roles' complete workflows using HTTP requests, integration tests, OpenAPI documentation, and synthetic fixtures.

Use three independent repositories: `xeats-backend`, `xeats-employee`, and `xeats-vendor`. The local `xeats-backend` repository now exists at `D:\Xeats\xeats-backend`; the employee and vendor repositories will be created when their application phases begin. The plan and contract are mirrored under the backend repository's `Docs` directory.

The backend is one modular Spring Boot application with one MySQL database. Its internal services are business modules, not separate deployed microservices. No message broker, Redis, or separate analytics service is required for the first release.

## 2. Confirmed product rules

1. A food court contains participating vendors; vendors operate counters. Each counter serves exactly one food item.
2. Each order is one meal from one counter. There is no cart, quantity selector, combined checkout, or multi-counter order.
3. An employee may have only one active order at a particular counter. They may have active orders at different counters simultaneously.
4. `ACCEPTED`, `PREPARING`, and `READY` are active states. `COLLECTED` and `EXPIRED` are terminal states and allow another order at that counter.
5. Valid orders are accepted automatically. There is no placed/pending approval state and no staff accept/reject workflow.
6. Normal lifecycle: `ACCEPTED -> PREPARING -> READY -> COLLECTED`.
7. Authorized counter staff can manually expire an active order. There is no automatic expiry, including at the end of a pickup window or service day. Overdue orders remain active until resolved.
8. There is no cancellation feature for employees or staff. Exceptional closure uses the explicitly permitted manual expiry action, with an explanation recorded.
9. Availability is `AVAILABLE` or `SOLD_OUT`. There are no numerical stock counts, inventory reservations, or last-unit guarantees.
10. Marking an item sold out blocks new orders and does not change existing orders.
11. A pickup window is optional input. Default to the earliest feasible available window; employees may choose a different available window.
12. Employees collect at the counter serving the item. Pickup-location selection is unnecessary.
13. Each order has its own token, redeemable once, only when ready, by authorized staff at that counter.
14. Usually one staff member operates a counter; multiple staff accounts can be assigned to the same counter. Staff authorization must not assume a one-to-one relationship.
15. Use fictional demo users and simulated successful payment. No corporate identity integration, real credentials, payment gateway, refunds, or settlement.

The earlier suggested default allowing collection before the selected window when an order is already `READY` is retained as a prototype assumption. The user has not separately specified an early-pickup restriction.

## 3. Proposed engineering defaults

- Java 21, Spring Boot 4.1.1, Spring Security, Spring Data JPA, MySQL 8.4.11, Flyway, Maven Wrapper 3.9.16, REST/OpenAPI, and SSE. These pins were verified during foundation setup; dependency updates require a deliberate compatibility check.
- Local tools and database setup are documented in `Backend-Toolchain.md`. Activate the project Java 21/Maven environment before builds; system Java remains version 17.
- Package by business capability: `identity`, `catalog`, `capacity`, `ordering`, `fulfillment`, `events`, `reporting`; a small `common` package holds genuinely shared infrastructure.
- Version API routes under `/api/v1`. Return DTOs rather than persistence entities; use consistent validation and machine-readable error codes.
- Use server-side cookie sessions for the single-instance prototype. Include CSRF protection for mutations, secure cookie settings in the deployed profile, and explicit origin configuration. The future frontends should use a same-origin `/api` proxy so browser sessions and native EventSource work together. A backend restart may require signing in again.
- Store instants in UTC and expose offset-qualified timestamps. Configure the campus timezone as `Asia/Kolkata` for opening hours and daily reports. Use an injectable clock in time-sensitive logic.
- Store prices as integer minor units with an explicit currency. Orders retain immutable snapshots of item name, price, counter identity/location, and selected slot boundaries.
- Seed accounts and fixtures only in explicitly enabled demo/test profiles. An authenticated `DEMO_ADMIN` manages structural setup and staff assignments; staff manage their assigned counters' operational settings and item details.
- A counter has an operational ordering switch and opening hours in addition to its item's two-state availability. This permits closing admission without adding an inventory state or removing fulfillment access.
- Preserve historical records. Deactivate catalog entries instead of deleting referenced data. A meal/name/price edit never changes an existing order's snapshot.
- Record a reason for manual expiry. An overdue flag is derived from time and state, not stored as another lifecycle state.
- One backend instance is sufficient for the initial hosted demo. Multi-instance event delivery and persistent sessions are later scaling work.

## 4. Backend capability map

| Module | Responsibilities | Primary consumers |
|---|---|---|
| Identity and access | Demo login/logout, current user, roles, counter assignments, owner/assignment checks | Both apps |
| Catalog and counter operations | Food courts, vendors, counters, single-item menu, prices, availability, ordering switch, opening hours | Employee discovery; staff controls; demo admin |
| Capacity and scheduling | Slot generation, walk-in allocation, online limits, advance horizon, default/selected slot validation | Employee checkout; staff capacity overview |
| Ordering | One-meal confirmation, active-order enforcement, idempotency, snapshots, simulated payment, employee order queries | Employee app |
| Fulfillment | Counter queues, preparing/ready actions, manual expiry, token verification and collection, audit timestamps | Vendor app |
| Live events | Authorized SSE streams for committed order, availability, and slot changes; reconnect resynchronization | Both apps |
| Reporting | Counter workload, completed/expired counts, item demand, hourly distribution, simulated order values | Vendor dashboard |

## 5. Minimum data model

| Record | Important contents and constraints |
|---|---|
| User / role | Fictional identity, password hash, enabled state, role |
| StaffCounterAssignment | Unique staff/counter pair; many accounts may serve one counter |
| FoodCourt / Vendor | Location hierarchy and enabled state; a vendor record is scoped to one food court in MVP |
| Counter | Vendor, location/instructions, ordering switch, opening hours, single assigned item |
| MenuItem | Unique counter relationship, name, description/category, price/currency, `AVAILABLE`/`SOLD_OUT` |
| CounterCapacityPolicy | Slot duration, total meal capacity, walk-in proportion, advance horizon, minimum preparation lead and service assumptions |
| PickupSlot | Counter, start/end instants, frozen online capacity and confirmed allocation count; unique counter/start pair |
| Order | Employee, counter, selected slot, immutable meal/price/location snapshots, state, simulated payment marker, lifecycle timestamps |
| ActiveOrderClaim | Primary key `(employee_id, counter_id)`, unique order reference; enforces one active order across concurrent requests |
| PickupToken | Cryptographically random human-readable code, unique constraint, order reference, redeemed timestamp; no token logging |
| OrderStatusEvent | Previous/new state, actor, timestamp, optional reason; written in the state-change transaction |
| IdempotencyRecord | Employee, operation/key, canonical request fingerprint, original result reference; unique employee/operation/key |

There is no quantity field accepted from clients, no multi-item order table, and no numerical Inventory entity. The server resolves the sole meal and authoritative price from the counter.

## 6. Scheduling and transaction rules

### Slot admission

- Use total meals per slot and a walk-in percentage. `onlineCapacity = floor(totalCapacity * (100 - walkInPercent) / 100)`. A zero-capacity slot is unavailable.
- Generate slots within opening hours and the advance-order horizon, respecting preparation lead time. Ensure generation is idempotent and closing-boundary/timezone behavior is tested.
- A slot response identifies its window, online capacity, remaining allocations, approximate readiness, and whether it can currently be selected.
- The pre-confirmation quote does not hold a slot. Confirmation is authoritative.
- Require a slot ID in the confirmation request, including when the app has preselected the earliest window. This ensures that a changed default is shown for confirmation rather than silently accepting a different promise. The API provides the recommended earliest slot in its quote response.
- When that slot fills or becomes infeasible, return a conflict and a refreshed recommendation. Do not silently move the order.
- For the prototype, a confirmed allocation remains consumed for its original slot after collection or expiry. Terminal actions release the employee's active-order claim, not the original service commitment. This conservative rule avoids reselling capacity already used by preparation or early collection.
- Capacity edits affect newly generated/unbooked future slots. Preserve booked slots and their commitments; do not shrink them under existing orders or silently move reservations.
- Walk-in capacity is a reservation in planning, not a second offline ordering application. Shared staff across independent-vendor counters must be accounted for in manually configured capacities; automatic workforce pooling is outside MVP.

### Atomic confirmation

Within one transaction: recognize idempotent replay; validate employee/role, counter admission, availability, active-order limit, and slot eligibility; allocate one slot unit; insert the accepted order and active claim; create its token and audit event; persist the idempotency result. Either all succeed or none do. Simulated payment is a marker in this transaction, not an external call.

Use MySQL constraints as well as transaction locking. A straightforward initial strategy serializes writes per counter using its row lock. Follow one documented lock order across ordering, availability, fulfillment, and settings changes. A sold-out toggle and order confirmation are serialized: orders committed before the toggle remain valid; orders admitted after it are blocked.

An identical retry with the same idempotency key returns the original order and token. A different payload using that key returns a conflict. A lost HTTP response must not create a second order.

### Fulfillment

Only allow the normal forward transitions and explicit active-to-expired transitions. Collection requires `READY`, the correct counter assignment, and a matching unused token. Collection/expiry atomically updates state, token validity, active claim, timestamps, and audit event. Competing collection, expiry, or staff updates must have one valid outcome.

### Estimates

Implement a deterministic, documented approximation using selected slot timing, configured service/preparation assumptions, and outstanding unready workload ordered by pickup commitment. Count a preparing meal conservatively unless actual remaining work is known. Changing workload/capacity must produce explainable results in fixture tests. If backlog makes a proposed window infeasible, omit it from available recommendations; if an accepted promise becomes late, expose the delay without moving its booked window.

The precise v1 formula and examples are frozen in `Backend-Contract.md` before scheduling code. Readiness time and physical pickup wait are separate fields. Physical pickup wait may be null with an explanation because walk-in arrivals and exact handover workload are not measured. Never infer a guaranteed zero-minute wait from a valid slot.

## 7. API surface

Chunk 0.1's detailed paths, request/response schemas, permissions, and error codes are authoritative in `Backend-Contract.md` and `openapi.yaml`.

| Area | Routes / behavior |
|---|---|
| Session | `GET /auth/csrf`, `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` |
| Discovery | `GET /food-courts`, food-court vendor listing, vendor counter listing, `GET /counters/{id}` |
| Ordering options | `GET /counters/{id}/order-options` with recommended slot, alternatives, availability, authoritative price, estimates |
| Employee orders | `POST /orders` with counter ID, slot ID, idempotency header; `GET /me/orders`; owner-scoped `GET /orders/{id}` including token |
| Staff workspace | `GET /staff/counters`, counter queue with status/window filters, workload and slot utilization |
| Staff fulfillment | Explicit preparing, ready, expire, and redeem actions under assigned-counter routes; no unrestricted status patch |
| Staff controls | Counter item details, availability, ordering switch, hours, capacity policy; changes limited to assigned counters |
| Demo administration | Structural food-court/vendor/counter setup and staff assignment endpoints; no public registration |
| Live updates | `GET /events`; subscription audience derived from authenticated identity and assignments, never caller-supplied user IDs |
| Reporting | Assigned-counter reports by date/range; counts, item demand, hourly distribution, simulated values |
| Operations | Health/readiness endpoints and documented OpenAPI surface |

Define stable codes for active-order conflicts, unavailable counters/items, full/ineligible slots, idempotency mismatch, invalid transitions, invalid/reused tokens, and access denial. Do not reveal another employee's orders or sensitive details in errors.

## 8. Live updates and reporting boundaries

Publish only committed changes. Use transaction-after-commit notifications for a single backend instance; a broker/outbox is unnecessary for this prototype. SSE is a notification channel, not the source of truth. On reconnect, clients re-fetch authorized snapshots; guaranteed replay is not promised. Provide heartbeat and reverse-proxy timeout guidance.

Filter streams and payloads by role and ownership/counter assignment. Do not broadcast pickup codes or employee order data to other employees. Document login/logout and expired-session handling for browser EventSource.

Reporting derives from order snapshots and lifecycle events. Separate accepted simulated value from collected simulated value; neither is settled revenue. Include expired orders instead of the obsolete cancellation/rejection metrics. Show missing timing data honestly.

## 9. Verification and release gates

Use real MySQL integration tests for schema, locking, and concurrency, via Testcontainers or an equivalent disposable MySQL environment. Do not substitute H2 for evidence about MySQL constraints and races.

Required evidence:

- Complete login -> discovery -> options -> order -> preparing -> ready -> redeem journey without a frontend.
- Same employee/same counter concurrent submissions yield one active order; different counters are allowed.
- Many employees competing for the final slot cannot oversubscribe it.
- Idempotent retry returns the original result, including after the order becomes terminal; changed payload conflicts.
- Sold-out/closure blocks new admission without changing accepted orders.
- Employee ownership and staff assignment restrictions cover reads, mutations, reports, and SSE.
- Concurrent token redemption, and collection racing expiry, cannot complete twice.
- Overdue orders never expire automatically. Manual expiry releases the active claim and invalidates collection.
- Opening boundaries, preparation lead, configuration changes, timezone reporting, and estimate fixtures behave predictably.
- Live events are emitted after commit; rollback emits none; reconnect uses authoritative reads.
- Clean database migration, repeatable demo seeding, and local container startup pass in Phase 6.1. Deployed HTTPS health and the hosted end-to-end smoke journey are Phase 6.2 gates and remain pending until a demo host is provisioned.

Deployment is to an independent demo environment with synthetic data. The hosting provider is intentionally deferred until the deployment chunk; its choice does not block domain implementation. Package a backend image plus MySQL configuration, HTTPS/reverse-proxy guidance, environment templates without secrets, and migration/backup/restore/rollback instructions. A database rollback must account for migration compatibility; reverting an application image alone is not always sufficient.

## 10. Phases and Luna handoff

The bounded work items, dependencies, and acceptance checks are in `Luna-Backend-Tasks.md`.

| Phase | Outcome | Chunks |
|---|---|---|
| 0 | Agreed rules translated into schemas, examples, and contracts | 0.1 |
| 1 | Bootable backend, database, authentication, demo identities | 1.1-1.3 |
| 2 | Discoverable counters, staff controls, available pickup windows | 2.1-2.3 |
| 3 | Atomic one-meal ordering and employee order queries | 3.1-3.3 |
| 4 | Staff fulfillment and exactly-once collection | 4.1-4.2 |
| 5 | Live updates, refined estimates, dashboard/report APIs | 5.1-5.3 |
| 6 | Full verification, packaging, hosted backend demo | 6.1-6.2 |

Implement one chunk at a time. Essential tests belong with the relevant behavior, not solely in Phase 6. Start frontend work after the deployed API passes its release gate and the API contract/examples are available to both app repositories.

## 11. Demo fixture profile

The `demo` Spring profile seeds only synthetic accounts and catalog data; its fixture runner is disabled when `prod` is also active. `XEATS_DEMO_PASSWORD` must be supplied at startup and is stored only as a BCrypt hash. Employees are `employee.demo.01` through `.03`; counter staff are `staff.north.thali`, `staff.north.wraps`, `staff.south.rice`, and `staff.independent.01` through `.03`; the demo administrator is `admin.demo`. All use the configured synthetic demo password.

Fixtures contain two courts, centralized-style and independent vendor records, six one-item counters, and recurring Monday–Saturday 11:00–14:00 Asia/Kolkata opening intervals. The seeder creates no fixed-date pickup slots. Re-running it upserts the hierarchy and assignments without duplicating them. For a clean demo, reset only the isolated Compose MySQL volume as documented in the backend README, then start the backend with the `demo` profile. Do not apply that destructive reset to shared development or production data.
