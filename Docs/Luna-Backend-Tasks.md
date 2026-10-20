# Luna backend implementation chunks

Status: Phase 0 chunk 0.1, Phase 1 chunks 1.1–1.3, and Phase 2 chunks 2.1–2.2 are complete; remaining chunks are planned. No agent has been dispatched.

## Handoff instructions

Read `D:\Xeats\xeats-backend\Docs\Backend-Plan.md` first, then the assigned chunk and its dependency outputs. The backend repository's `Docs` directory contains the current plan, contract, OpenAPI definition, toolchain notes, and this task list. User decisions in the plan override the older `Xeats.md` brief.

Read `Backend-Toolchain.md` for the installed environment. Before building in PowerShell, dot-source `D:\Xeats\scripts\Use-BackendTools.ps1`; the unmodified system default is Java 17. Docker/MySQL setup is already provided in the planning workspace. Reuse it while establishing the backend repository instead of installing a second database or silently using a different JDK.

Implement only the assigned chunk plus necessary fixes to its dependencies. Keep business rules on the server. Do not create Angular applications, numeric inventory, carts, quantity fields, cancellation/rejection features, corporate integrations, payment integrations, microservices, or speculative infrastructure.

Do not postpone relevant permission, transaction, or concurrency tests to the final phase. Use disposable real MySQL for database-specific assertions. Document assumptions in the backend's decision log and preserve the confirmed product rules. Use the existing setup and report any remaining environment failures accurately. Do not turn environment setup into unrelated application work.

At the end of each chunk, report: delivered behavior, changed files, commands/checks and their actual outcomes, any unrun checks and why, and the next dependent chunk. Do not claim tests or deployment succeeded without evidence. If repository commits are part of the chosen workflow, keep them scoped to the assigned chunk.

Reusable assignment prompt:

> Implement chunk <ID> from Docs/Luna-Backend-Tasks.md in the xeats-backend repository. Read Docs/Backend-Plan.md and applicable repository instructions first. Complete the chunk's implementation and acceptance checks, update its status/evidence, and report the result. Preserve the agreed scope and do not start another chunk.

The planning documents have been copied into `D:\Xeats\xeats-backend\Docs`; update that repository copy when shared decisions change.

## Phase 0: rules and contracts

### 0.1 — Freeze the backend contract

Dependencies: none.

Status: **COMPLETE.** Contract and hand-checkable scenarios are in `Docs/Backend-Contract.md`; the OpenAPI 3.1 definition is in `Docs/openapi.yaml`.

Deliver:
- A decision log containing the confirmed rules and clearly labeled technical defaults from the plan.
- An initial OpenAPI contract for identity, discovery, ordering options, employee orders, staff operations, events, and reports. Define DTOs, errors, timestamps, money, pagination, role requirements, and idempotency.
- A data relationship/schema design with active-order uniqueness, token uniqueness, order snapshots, and lifecycle history.
- Exact v1 slot/ETA rules with examples: empty counter, full slot, future slot, delayed backlog, zero online capacity, and closing time. Keep physical-wait estimates nullable when inputs are missing. Prefer a simple conservative algorithm over invented precision.
- A permission matrix and state-transition matrix. Document manual expiry from active states, no automatic expiry, early collection once ready, and retained slot allocations after terminal actions.
- A single lock ordering/transaction approach and request retry semantics, including same-key replay after collection/expiry.

Acceptance: **MET.** The contract fixes expected requests, responses, permitted actors, state changes, conflict outcomes, scheduling assumptions/formula/examples, persistence/constraints, role matrix, transactions, and retry behavior. The OpenAPI schema has no quantity, cart, cancellation, rejection, or numerical inventory fields. `python scripts/validate_backend_contract.py` passed: OpenAPI 3.1 parsed with unique YAML keys, all local references resolved, operation IDs and path parameters checked, core Xeats invariants checked, and the SSE example validated against its event envelope. The validator requires PyYAML 6.0 or newer.

## Phase 1: foundation and identity

### 1.1 — Scaffold the backend and database lifecycle

Dependencies: 0.1.

Status: **COMPLETE.** The standalone `xeats-backend` repository is scaffolded under `D:\Xeats\xeats-backend` with the pinned Java 21 / Spring Boot 4.1.1 / Maven Wrapper 3.9.16 stack, MySQL baseline migration, local configuration, error handling, health probes, CI, and copied project documentation.

Deliver: the backend repository structure; pinned Java/Spring/Maven dependencies and wrapper; module packages; local/test/demo configuration; Flyway baseline including the food-court/vendor/counter/single-item tables needed by subsequent staff assignments; MySQL Compose configuration; consistent error handling and validation; health/readiness; an initial CI build. Add UTC time handling and an injectable clock. Business entities and fixtures can follow in 1.3.

Acceptance: wrapper build passes, the app starts against MySQL, migrations work on an empty database and a subsequent restart, health/readiness behave correctly when the database is unavailable, and no secrets are committed. Do not implement business flows here.

Evidence: `mvnw.cmd -B -ntp '-Dmaven.repo.local=D:\Xeats\.tools\m2' verify` passed with 4 tests; the Testcontainers integration test migrated disposable MySQL 8.4.11 and verified readiness goes DOWN while liveness remains UP after database loss. The standalone Compose file passed `docker compose ... config --quiet`. Against the shared local MySQL 8.4.11 database, the app started twice, reported readiness `UP` both times, and Flyway reported the schema up to date on the second start. The root and repository-local OpenAPI consistency scripts pass. Secrets remain in ignored local `.env` files; only placeholders are committed.

### 1.2 — Implement identity and authorization foundations

Dependencies: 1.1.

Status: **COMPLETE.** Identity persistence, session login/logout/current-user and CSRF endpoints, role and counter-assignment authorization, origin configuration, and production cookie settings are implemented. Demo account creation remains in 1.3.

Deliver: users/roles and staff-counter assignment persistence; password hashing; demo session login/logout/current-user and CSRF handling; route security; reusable owner/counter assignment authorization. Use secure deployed cookies and explicit browser origin settings.

Acceptance: employees and staff authenticate; anonymous access is blocked as specified; logout invalidates the session; invalid/missing CSRF is rejected on mutations; role escalation and unauthorized counter access fail. Document how both future Angular apps and EventSource use the session.

Evidence: `mvnw.cmd -B -ntp '-Dmaven.repo.local=D:\Xeats\.tools\m2' verify` passed with 6 tests against disposable MySQL 8.4.11. HTTP integration checks cover employee/staff logins, session ID rotation, generic invalid-credential responses, `/auth/me`, credentialed CORS allowlisting, missing and invalid CSRF rejection, logout/session invalidation, and role denials including role revocation during a session. Reusable authorization checks deny an unassigned counter and hide an order owned by another employee; a controlled-clock test verifies configurable login throttling. `application-prod.yml` requires explicit `XEATS_ALLOWED_ORIGINS` and sets Secure session cookies. No demo identities are seeded before chunk 1.3.

### 1.3 — Add deterministic synthetic fixtures

Dependencies: 1.2. This chunk adds catalog entities over the schema from 1.1 and the fixture loader; reuse them in Phase 2.

Status: **COMPLETE.** Typed catalog entities, JDBC persistence, recurring opening-hours configuration, and profile-gated synthetic fixtures are implemented.

Deliver: food-court/vendor/counter/single-item entities and idempotent demo seeding for two food courts, centralized-style and independent vendors, multiple employees, one-staff counters, and a counter with multiple authorized staff. Document demo accounts and demo-profile-only reset procedures. Seed time-relative configuration rather than permanently stale slot dates.

Acceptance: repeated seeding creates no duplicates; each counter has exactly one item; fixtures contain no real people or credentials; normal deployment profiles do not silently seed demo accounts.

Evidence: `mvnw.cmd -B -ntp '-Dmaven.repo.local=D:\Xeats\.tools\m2' verify` passed with 8 tests against disposable MySQL 8.4.11. Integration assertions seed twice and verify stable counts for two courts, four vendors, six counters with exactly one meal each, ten synthetic users, eight staff assignments, and 36 recurring opening intervals. Profile checks confirm the runner is absent by default and whenever `prod` is active, and present for `demo`. Demo usernames, the required externally supplied `XEATS_DEMO_PASSWORD`, and the isolated Compose-volume reset procedure are documented in the backend README and plan. No dated pickup slots or real credentials are seeded.

Next: implement 2.1 employee discovery APIs using the catalog entities and fixtures.

## Phase 2: discovery, operations, scheduling

### 2.1 — Implement employee discovery APIs

Dependencies: 1.3.

Status: **COMPLETE.** Added authenticated food-court, vendor, counter-list, and counter-detail discovery using public DTOs and stable ordering. Results filter inactive courts/vendors/counters and omit incomplete counters without their single menu item. Menu availability, online-order admission, prices, and pickup instructions are exposed without staff assignments or user data.

Deliver: food-court, vendor, counter, and meal discovery DTOs; ordering/availability indicators; authoritative prices and pickup instructions; filtering of configured locations; stable pagination where needed. Do not expose staff-only fields or persistence entities.

Acceptance: fixture hierarchy is returned correctly; every counter exposes one meal; unavailable counters are represented consistently; employee requests do not expose other users or administrative data.

Evidence: full `mvnw.cmd -B -ntp '-Dmaven.repo.local=D:\Xeats\.tools\m2' verify` passed with 9 tests: 5 integration tests against disposable MySQL 8.4.11 and 4 unit tests. The discovery HTTP assertions verify anonymous access is denied, authenticated employee and staff access succeeds, public responses omit staff/user data, inactive hierarchy entries are filtered, sold-out and online-order states are represented, and an inactive counter returns 404. The root OpenAPI validator also passed.

Next: proceed to 2.2 catalog administration and counter controls.

### 2.2 — Implement catalog administration and counter controls

Dependencies: 2.1.

Status: **COMPLETE.** Demo-admin structural setup and staff assignment endpoints are implemented. Assigned staff and demo admins can edit the single menu item's details and price, set its two-state availability, open or close online admission, and replace weekly local opening hours. Counter creation atomically saves one meal, an initial capacity policy, and opening intervals. Counter settings and staffing changes are audited; deactivation preserves catalog, assignment, policy, and opening-hour records.

Deliver: demo-admin structural setup and staff assignment APIs; assigned-staff item/price editing, `AVAILABLE`/`SOLD_OUT`, ordering switch, and opening-hours settings. Enforce one item per counter and preserve referenced history. Audit important operational changes.

Acceptance: staff from another counter cannot mutate settings; multiple assigned staff can operate the same counter; deactivation does not delete historical references; a two-state availability contract is maintained. Add order-impact assertions when ordering exists in Phase 3.

Evidence: full `mvnw.cmd -B -ntp '-Dmaven.repo.local=D:\Xeats\.tools\m2' verify` passed with 10 tests: 6 integration tests against disposable MySQL 8.4.11 and 4 unit tests. HTTP assertions cover admin-only structure changes, duplicate food-court code conflicts, active vendor-staff assignment validation and idempotency, multiple staff assigned to one counter, denial of another counter's staff, counter operations by assigned staff and demo admin, two-state availability, separate online admission, cross-midnight opening hours and overlap rejection, audit records, and deactivation without deleting references. Both OpenAPI contract validators passed.

Next: implement 2.3 capacity policies and pickup options.

### 2.3 — Implement capacity policies and pickup options

Dependencies: 2.2 and the 0.1 scheduling contract.

Status: **COMPLETE.** Request-driven quotes materialize whole pickup windows under a per-counter row lock and unique `(counter_id, start_at)` key. Windows retain their policy revision and capacity snapshots. Assigned staff can create audited policy revisions and view capacity; employee quotes return the earliest recommendation and later options, and never change allocation counts. Opening intervals must align to the configured slot duration. Active-order backlog remains zero until Phase 3 adds order persistence.

Deliver: capacity policy persistence/settings; idempotent slot generation; walk-in allocation; online slot limits; preparation lead, hours, and horizon enforcement; recommended earliest slot and optional alternatives in ordering-options responses. Provide a scheduled/idempotent refresh or request-driven materialization strategy with database uniqueness.

Acceptance: deterministic-clock tests cover zero capacity, rounding of walk-in allocation, full/unavailable windows, opening/closing boundaries, horizons, and concurrent slot generation. Policy edits preserve booked commitments once orders exist. A quote never reserves capacity.

Evidence: full `mvnw.cmd -B -ntp '-Dmaven.repo.local=D:\Xeats\.tools\m2' verify` passed with 16 tests: 7 integration tests against disposable MySQL 8.4.11 and 9 unit tests. The fixed-clock HTTP integration test covers policy revision and audit persistence, concurrent quote materialization, quote non-reservation, full and zero-capacity windows, walk-in rounding, and frozen capacity snapshots. Deterministic planner tests cover opening/closing boundaries, preparation lead, horizons, capacity rounding, and ETA positions. Both mirrored OpenAPI validators passed with 33 paths, 45 schemas, and 233 references.

Next: proceed to 3.1 order persistence and invariant primitives.

## Phase 3: employee ordering

### 3.1 — Implement order persistence and invariant primitives

Dependencies: 2.3.

Status: **COMPLETE.** Migration V6 persists one-meal orders with frozen item, price, counter, pickup-instruction, and slot-window snapshots; active claims; unique pickup tokens; versioned status events; and employee/operation-scoped idempotency records. Order state transitions use the shared counter-row lock and update history, token state, and active claims in one transaction. No order mutation HTTP endpoints are exposed yet.

Deliver: order, active-order claim, token, status-event, and idempotency migrations/entities; immutable meal/price/location/slot snapshots; the state machine; shared per-counter lock acquisition and claim lifecycle operations. Avoid exposing partially implemented order mutations.

Acceptance: MySQL constraints reject duplicate active claims and token collisions; terminal state handling releases claims exactly once; invalid state transitions fail. Verify historical snapshots survive menu/price edits.

Evidence: `mvnw.cmd -B -ntp '-Dmaven.repo.local=D:\Xeats\.tools\m2' verify` passed with 19 tests: 8 integration tests against disposable MySQL 8.4.11 and 11 unit tests. Real-MySQL assertions cover V6 migration, duplicate employee/counter claims, duplicate token codes, frozen snapshots after menu edits, idempotency lookup/retention after expiry, and atomic expiry/collection with token invalidation or redemption, claim release, and status-history writes. State-machine unit tests reject skipped, repeated, and post-terminal transitions.

Next: implement 3.2 atomic order confirmation.

### 3.2 — Implement atomic order confirmation

Status: **COMPLETE.** `POST /api/v1/orders` accepts exactly one counter and selected pickup slot, immediately accepts a valid meal with simulated payment, and commits its immutable snapshots, unique token, active-order claim, capacity allocation, status event, and idempotency record atomically. Identical retries return the original order and token; changed requests using a successful key conflict. Employees can hold concurrent active orders at separate counters, with one active order per counter.

Dependencies: 3.1.

Deliver: `POST /orders` with required counter/slot and an idempotency key; automatic acceptance, simulated payment, capacity allocation, active claim, token, and status event in one transaction. Validate current admission/availability, employee identity, slot feasibility, and active-order eligibility. Replays return the original result; fingerprint mismatches conflict.

Acceptance using concurrent real-MySQL tests:
- Many requests by one employee for one counter yield one active order.
- Different counters permit simultaneous orders for that employee.
- Many employees competing for the last slot unit cannot oversubscribe it.
- Same-key concurrent retries produce one original result; same key/different payload conflicts.
- A forced failure leaves no partial order/claim/token/allocation.
- Sold-out/closure racing confirmation follows the documented serialization rule.
- A stale selected/default slot conflicts and provides refreshed options; no silent reassignment.

Evidence: `mvnw.cmd -B -ntp '-Dmaven.repo.local=D:\Xeats\.tools\m2' verify` passed with 21 tests: 10 HTTP/MySQL integration tests against disposable MySQL 8.4.11 and 11 unit tests. Integration coverage includes eight concurrent same-key submissions, exact create-response replay after manual expiry, concurrent orders across two counters, three employees competing for a one-meal slot, a sold-out/confirmation race, explicit sold-out and paused-admission conflicts, strict rejection of a `quantity` field, stale-slot refreshed options, and a forced late transaction failure proving all writes roll back and the same key can be retried.

Next: implement 3.3 employee order reads and HTTP journey examples.

### 3.3 — Implement employee order reads and HTTP journey examples

Dependencies: 3.2.

Deliver: current-user active/history queries, owner-scoped detail/token retrieval, stable status/location/slot responses, filtering/pagination, and an executable HTTP/API collection covering employee checkout. Connect order-option remaining capacity to confirmed allocations.

Acceptance: employee A cannot read employee B's order/token; multiple counters' active orders appear together; an overdue active order remains active; stale availability/options are refreshed from authoritative state. Initially verify expiry/collection effects with fixtures, then exercise real transitions in Phase 4.

Status: **COMPLETE.** Employee list reads default to all active orders across counters, support repeated status filters and stable newest-first pagination, and omit pickup secrets. Owner-scoped detail returns the stored token, current redeemability, an order-version ETag, pickup location/window, and derived overdue state; unknown and non-owned orders both return 404. Order options continue to calculate remaining capacity from confirmed allocations, now verified by an HTTP read after checkout. Added an executable REST Client journey at `scripts/employee-checkout.http`.

Evidence: `mvnw.cmd -B -ntp '-Dmaven.repo.local=D:\Xeats\.tools\m2' verify` passed with 22 tests: 11 HTTP/MySQL integration tests against disposable MySQL 8.4.11 and 11 unit tests. Integration checks cover empty active queries, cross-counter aggregation, history filters (including repeated states), pagination, owner-token isolation, expired and collected token snapshots, overdue active orders retaining their claim, order-option allocation/remaining values, and invalid page sizes. Both OpenAPI contract validators passed.

Next: implement 4.1 staff queues and state changes.

## Phase 4: counter fulfillment

### 4.1 — Implement staff queues and state changes

Dependencies: 3.3.

Deliver: assigned-counter workspace, queues sorted by pickup commitment and preparation priority, filters, preparing/ready actions, and manual expiry with an audit reason. Make overdue status derived; keep accepted orders actionable after sold-out/closure. Reject stale or invalid mutations with consistent errors.

Acceptance: only assigned staff act; multiple staff making conflicting changes cannot overwrite each other silently; no reverse transitions; expiry is manual and releases the active claim while retaining the original slot allocation. A new same-counter order is possible after expiry. Same-key replay still returns the original expired order.

Status: **COMPLETE.** Staff can view assigned-counter workspaces and filtered, paged queues ordered by pickup commitment and readiness estimate. Preparing, ready, and reasoned manual-expiry actions require the current order ETag; stale updates return 412 with the latest order. Orders remain actionable after a counter or item closes, overdue is derived from the committed pickup window, and expiry releases the active claim without freeing booked capacity.

Evidence: `mvnw.cmd -B -ntp '-Dmaven.repo.local=D:\Xeats\.tools\m2' verify` passed with 23 tests, including 12 HTTP/MySQL integration tests against disposable MySQL 8.4.11. The staff integration journey verifies assigned and unassigned access, queue filters and overdue state, closed/sold-out fulfillment, concurrent conflicting updates, rejected reverse transitions, expiry reason/audit, claim and allocation behavior, same-key replay, and a subsequent same-counter order. Both OpenAPI contract validators passed.

Next: implement 4.2 token verification and collection.

### 4.2 — Implement token verification and collection

Dependencies: 4.1.

Deliver: counter-scoped token redemption, optional QR payload format, ready-only validation, collection timestamp/event, token invalidation, and atomic active-claim release. Keep token exposure owner-scoped and exclude token values from logs/broadcasts.

Acceptance: invalid, wrong-counter, not-ready, expired, and reused tokens fail appropriately. Concurrent redemption collects once. Redemption racing expiry has one terminal outcome. Ready meals may be collected before their slot. A new same-counter order is possible after collection, and retrying the original creation key still returns the original order.

Status: **COMPLETE.** Assigned staff can redeem a 10-character pickup code only at its matching counter and only after the order is READY. Collection records the timestamp and status event, marks the token redeemed, releases the active claim, and retains the slot allocation in one counter-serialized transaction. Unknown, wrong-counter, not-ready, expired, and reused codes receive the same generic error. Failed scans are rate-limited per staff account and counter; pickup codes do not appear in the redemption response or error logs.

Evidence: `mvnw.cmd -B -ntp '-Dmaven.repo.local=D:\Xeats\.tools\m2' verify` passed with 25 tests, including 13 ordered integration tests against disposable MySQL 8.4.11. The redemption journey verifies assignment scope, early READY collection, failed-code cases, one-time concurrent redemption, expiry racing redemption, active-claim release, retained slot allocation, idempotent create replay, reordering at the counter after collection, and the 10-failure/one-minute throttle. The limiter unit test verifies staff/counter isolation and reset after success or lockout. Both OpenAPI contract validators passed.

Next: implement 5.1 authorized live notifications.

## Phase 5: live experience and reporting APIs

### 5.1 — Implement authorized live notifications

Dependencies: 4.2.

Deliver: session-authenticated SSE; order-status, availability, and slot-capacity notifications published after commit; per-user/assignment audience checks; heartbeat and disconnect handling; client reconnect/refetch contract. Use the single-instance architecture without a broker.

Acceptance: rollback sends no event; unrelated employees/counters receive no private events; no pickup codes are broadcast; expired/logout sessions cannot continue receiving private data; reconnect followed by snapshot reads recovers current truth. Provide a two-session demonstrator or automated integration check.

Status: **COMPLETE.** `GET /api/v1/events` streams privacy-safe SSE envelopes from a single-instance in-memory hub. Order, availability, admission, opening-hours, and slot-capacity notifications are registered within their write transactions and sent only after commit. Every event rechecks the live session, enabled account, current role, employee ownership, and staff counter assignment. Five-second comment heartbeats detect disconnects and invalidated sessions; reconnect does not replay events, so clients refetch authorized snapshots. Automated integration coverage verifies rollback suppression, employee and staff audience boundaries, assignment removal, logout, code-free payloads, and reconnect/refetch. Full Maven verification passed; both OpenAPI contract validators passed.

### 5.2 — Complete workload and ETA projections

Dependencies: 5.1. Use the exact formula established in 0.1; this chunk integrates and verifies it against the complete lifecycle rather than inventing a new admission policy.

Deliver: queue workload, due/overdue counts, slot utilization, approximate readiness updates, and separate nullable physical-wait estimate fields. Integrate current accepted/preparing/ready data and explain estimate assumptions in API documentation. Surface delayed commitments without rewriting booked windows.

Acceptance: controlled-clock examples show predictable effects from extra orders, preparation/readiness, manual expiry, service settings, and capacity changes. Ready orders use their actual readiness timestamp. Missing pickup-wait inputs are represented honestly, and no estimate grants extra booking capacity.

Status: **COMPLETE.** A shared projection now applies the frozen formula to counter-wide ACCEPTED/PREPARING workload in quotes, employee order responses, staff queues, and capacity windows. READY/COLLECTED orders expose their actual `readyAt`; expired orders without a readiness timestamp stop contributing to workload but retain their allocated slot position. Staff counters report disjoint due and overdue counts, and staff capacity includes earlier unready workload and the latest readiness estimate for each occupied slot. Physical onsite wait remains explicitly null with its reason. Integration coverage verifies quotes, employee confirmation responses, staff capacity, due/overdue behavior, and manual expiry without returning slot capacity. The controlled-clock projection, pickup-planner, and MySQL integration suite passed: 29 tests total; both OpenAPI contract validators passed.

### 5.3 — Implement dashboard and demand reporting

Dependencies: 5.2.

Deliver: assigned-counter dashboard counts and reports for completed/expired orders, meals collected by item, hourly demand, lifecycle timings, and separately labeled simulated accepted/collected values. Filter by date/range in the campus timezone; aggregate only authorized counters. No settlement/revenue claims.

Acceptance: fixtures with known orders produce exact counts/values; menu edits do not rewrite historic item/price reporting; cross-counter data remains protected; date-boundary and missing-timestamp cases are correct.

Status: **COMPLETE.** `GET /api/v1/staff/counters/{counterId}/report` now returns campus-local date-bounded accepted, collected, on-time, and expired counts; simulated accepted/collected values; historic collected meals grouped by item snapshot; 24 hourly demand bins; and collected-order lifecycle timing summaries. Ranges are inclusive and limited to 31 local dates. Event cohorts use the corresponding event timestamp, and only staff assigned to the counter can read it (with the established demo-admin override). Composite counter/timestamp indexes support the four event-range queries. Timing summaries expose sample count, average, and nearest-rank p95; empty or missing duration samples return null averages/percentiles. Integration fixtures prove exact aggregates, midnight boundaries, historical item/price stability after menu edits, expired/completed totals, empty timing samples, invalid ranges, and employee/cross-counter denial. Full backend verification passed: 30 tests; OpenAPI contract validation passed.

## Phase 6: release the backend before frontend work

### 6.1 — Verify the complete backend and prepare its release

Dependencies: 5.3.

Deliver: complete API journey scripts, integration/concurrency/security regression coverage, final OpenAPI contract and sample payloads for both app teams, CI gates, realistic bounded admission-load checks, and operational logs/health with sensitive fields excluded. Close integration gaps; do not rewrite functioning modules or repeat broad tests without a reason.

Acceptance: all release-gate scenarios in Backend-Plan.md pass against a clean MySQL instance. Record actual commands/results and realistic test load. Document known limitations: toggle-only stock, manual expiry, approximate ETA, unmeasured walk-ins, single backend instance, and reconnect/refetch semantics.

Status: **COMPLETE.** Prepared the backend for the user's GitHub push with the complete employee/staff HTTP handoff, representative API/SSE/error payloads, reviewed CI gates, operational health/logging checks, and a recorded release-readiness report. The CI workflow runs the full Maven/Testcontainers suite and the OpenAPI/business-contract validator.

Evidence: fresh Testcontainers MySQL 8.4.11 verification passed all 30 tests (15 HTTP/MySQL integration tests and 15 unit tests); all seven migrations applied to the empty database. Twenty-four distinct employee sessions raced for a final online slot: one order was accepted, 23 received `409 SLOT_UNAVAILABLE`, and allocation increased once. Two simultaneous orders by the same employee for one counter produced one accepted order and one `409 ACTIVE_ORDER_EXISTS`. The OpenAPI/business-contract validator passed from both the backend and workspace documentation roots (33 paths, 33 operations, 46 schemas, 240 local references). GitHub Actions has not run yet; it will run after the user's push. Hosted deployment and HTTPS smoke checks remain in 6.2.

### 6.2 — Package and deploy the independent demo backend

Dependencies: 6.1. Hosting account/environment details are needed here, not during earlier domain chunks.

Deliver: backend container, runtime configuration templates, database connection/migration procedure, HTTPS/reverse-proxy and SSE settings, deployment instructions or pipeline, backup/restore and compatible rollback procedure, and configured demo-profile provisioning. Choose the provider with the user at this stage. Run deployment only in the authorized independent environment.

Acceptance: hosted readiness is healthy over HTTPS; schema migration and controlled seed setup work; two demo identities complete the hosted order/fulfill/redeem journey; unauthorized access and duplicate pickup fail; SSE works through the deployed proxy; restart/reconnect behavior is documented. Supply the actual API base URL, API documentation access, smoke-test results, and release identifier.

Do not call the backend deployed merely because an image builds or localhost is reachable. If external provisioning is outstanding, report the exact missing step and preserve the ready-to-deploy release.

## Completion gate

Backend implementation is complete when chunk 6.2's hosted checks pass and both frontend repositories can consume the published API contract and demo environment. Only then begin employee/vendor application implementation under a separate frontend plan.
