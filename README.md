# Xeats Backend

The shared Spring Boot backend for the employee and counter-staff applications. This is a modular monolith with one MySQL database; the domain modules are packages in one deployable service.

## Tool versions

- Eclipse Temurin Java 21
- Spring Boot 4.1.1
- Maven Wrapper distribution 3.9.16
- MySQL 8.4.11

The wrapper is the project build entry point. On the current Xeats workspace, activate the verified toolchain from PowerShell with `. ..\scripts\Use-BackendTools.ps1`; the system default Java remains unchanged.

Identity and server-side sessions are implemented. Synthetic catalog and account fixtures are available only with the `demo` Spring profile (and are disabled when `prod` is also active). Employee ordering and order history, assigned-counter staff controls and fulfillment, one-time pickup redemption, scoped live events, readiness projections, and counter reporting are implemented and locally verified. Hosted deployment remains a separate follow-up; this repository is not yet a deployed service.

## Local database

In this workspace, reuse the existing development MySQL service on `127.0.0.1:3307`:

```powershell
..\scripts\Start-BackendDatabase.ps1
. .\scripts\Use-WorkspaceDatabase.ps1
```

Then start the service:

```powershell
.\mvnw.cmd spring-boot:run
```

The helper reads the ignored workspace credential file into the current PowerShell session and does not print the password. The default JDBC URL targets the shared development database. To use a standalone checkout, copy `infra/.env.example` to `infra/.env`, replace both placeholder passwords, start `infra/compose.yaml`, and set `SPRING_DATASOURCE_URL` to `jdbc:mysql://127.0.0.1:3308/xeats?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`.

## Browser sessions

The session API is under `/api/v1/auth`: fetch `/csrf`, send its token in `X-XSRF-TOKEN` when posting to `/login`, then fetch a fresh token after login and logout before the next mutation. The server sets an HttpOnly `JSESSIONID` cookie. Both future Angular apps should use the same-origin `/api` proxy, send browser credentials, and keep the returned CSRF token in an HTTP interceptor that adds `X-XSRF-TOKEN` to mutations. Same-origin `EventSource` then uses the session cookie automatically. After an event stream reconnects, fetch the current state again because event replay is not promised. `/me` returns the current identity and live counter assignments.

Local CORS allows `http://localhost:4200` and `http://localhost:4201`. For deployment, activate the `prod` profile and set `XEATS_ALLOWED_ORIGINS` to a comma-separated list of exact HTTPS app origins. The production profile always sets the session cookie's Secure flag and fails startup if allowed origins are omitted. Session timeout and login attempt throttling are configurable with `XEATS_SESSION_TIMEOUT`, `XEATS_LOGIN_MAX_ATTEMPTS`, `XEATS_LOGIN_WINDOW`, and `XEATS_LOGIN_LOCKOUT`.

## Synthetic demo fixtures

Set `SPRING_PROFILES_ACTIVE=demo` and provide `XEATS_DEMO_PASSWORD` before starting the backend. The runner requires a nonblank password and stores only its BCrypt hash. Every synthetic account uses that password; it is not stored in the repository. The stable usernames are:

- Employees: `employee.demo.01`, `employee.demo.02`, and `employee.demo.03`.
- Counter staff: `staff.north.thali`, `staff.north.wraps`, `staff.south.rice`, and `staff.independent.01` through `staff.independent.03`.
- Demo administrator: `admin.demo`.

The seeded hierarchy has two food courts, centralized-style and independent vendors, six counters with one meal each, one-staff and shared-staff counters, and recurring Monday–Saturday opening intervals from 11:00 to 14:00 in `Asia/Kolkata`. It creates no dated pickup slots. Seeding is transactional and repeatable; it does not delete orders or other data. `demo` does not seed if the `prod` profile is also active.

To reset a local demo, stop the backend and remove the MySQL volume belonging to the isolated Compose database only, then restart that database and the backend with the `demo` profile. For example, from this repository, `docker compose --env-file infra/.env -f infra/compose.yaml down --volumes` removes the Compose database volume; `docker compose --env-file infra/.env -f infra/compose.yaml up -d` recreates it. This drops all data in that Compose database. Never use this reset on the shared development database or any production database. Starting the demo backend after the reset runs migrations and recreates the fixtures.

## Build and verification

```powershell
.\mvnw.cmd -B -ntp verify
python -m pip install -r .\scripts\requirements-contract.txt
python .\scripts\validate_backend_contract.py
```

The integration test starts clean disposable MySQL 8.4.11 through Testcontainers. It checks schema migrations, repeatable fixture seeding, full order and fulfillment journeys, concurrent final-slot admission, same-employee races, idempotent replay, owner/counter authorization, CSRF enforcement, live-event scope/reconnect, report boundaries, and health/readiness after database loss. The ordinary local database is not stopped or reset by tests. GitHub Actions runs both Maven verification and the OpenAPI/business-contract validator.

## Documentation

- `Docs/Backend-Plan.md` — architecture and delivery plan
- `Docs/Backend-Contract.md` — product decisions, persistence invariants, concurrency rules, capacity and ETA behavior
- `Docs/openapi.yaml` — HTTP contract for both future applications
- `Docs/API-Examples.md` — representative employee, staff, event, and report payloads
- `Docs/Release-Readiness.md` — local release-gate evidence and known limitations
- `Docs/Luna-Backend-Tasks.md` — implementation chunks and acceptance checks
