# Backend development environment

Setup date: 19 October 2026. This is local tooling and infrastructure, not implementation of the Xeats backend.

## Installed and existing tools

| Component | Version / location |
|---|---|
| Eclipse Temurin JDK | Java 21.0.12.1+1 LTS, installed under `D:\Xeats\.tools\jdks` |
| Apache Maven | 3.9.16, installed under `D:\Xeats\.tools\maven` |
| Git | Existing Git 2.54.0.windows.1 |
| Docker Engine | Existing Docker Desktop installation, engine 29.5.3 |
| Docker Compose | Existing Compose v5.1.4 |
| MySQL | Official `mysql:8.4` container, verified server version 8.4.11 |
| Node / npm | Existing Node 24.16.0 and npm 11.13.0; frontend tooling is not required yet |

The existing system Java 17 remains installed. Java 21 and Maven are activated per terminal rather than replacing global settings. Downloaded archives were checked against the publishers' SHA-256 (JDK) and SHA-512 (Maven) checksums. Installed paths, sources, and hashes are recorded in `.tools\installed-tools.json`.

## Use a new PowerShell terminal

```powershell
Set-Location D:\Xeats
. .\scripts\Use-BackendTools.ps1
java -version
mvn.cmd --version
```

Dot-sourcing (the leading `. `) makes the environment apply to the current terminal. Future Luna implementation commands must activate these tools before building. Use `mvn.cmd` on Windows, or the backend repository's Maven wrapper once it exists.

Dependencies already downloaded for verification are cached under `D:\Xeats\.tools\m2`. To reuse that cache before the backend repository has its own wrapper/configuration:

```powershell
mvn.cmd '-Dmaven.repo.local=D:\Xeats\.tools\m2' verify
```

## Development database

Start Docker Desktop, then:

```powershell
.\scripts\Start-BackendDatabase.ps1
.\scripts\Test-BackendTools.ps1
```

- Address: `127.0.0.1:3307` (bound to localhost only).
- Database: `xeats`.
- Application user: `xeats_app`.
- JDBC URL: `jdbc:mysql://127.0.0.1:3307/xeats?connectionTimeZone=UTC`.
- Random application/root passwords: local ignored file `D:\Xeats\infra\.env`. Do not commit this file or copy its contents into logs.
- Configuration: `infra\compose.yaml`; persistent data lives in Docker volume `xeats-dev_mysql-data`.
- The backend's database-password environment variable should be populated from the local application password when its runtime configuration is implemented. No backend configuration exists yet.
- The database service uses `restart: unless-stopped`, so it starts when Docker starts unless explicitly stopped.

Stop the database while retaining its data:

```powershell
docker compose -f D:\Xeats\infra\compose.yaml stop
```

Do not delete the volume or regenerate passwords for an initialized database as a routine restart procedure. Changing the `.env` password alone does not change an existing MySQL user's password.

## Framework verification

An ignored, temporary project exists at `.tools\verification\stack-check`, generated from official Spring Initializr for Java 21. Its Maven parent was normalized from Initializr's unresolvable `4.1.1.RELEASE` identifier to the published `4.1.1` artifact after checking Maven Central.

It resolves Spring Boot 4.1.1, Web MVC, Security, validation, JPA/Hibernate, Actuator, Flyway with MySQL support, MySQL Connector/J, JUnit, and Testcontainers 2.0.5. These are project dependencies managed by Maven, not separate Windows programs. The product's final dependency pins belong to its foundation chunk.

The check uses `mysql:8.4`, not MySQL's moving `latest` tag. Its test starts disposable MySQL through Testcontainers, loads the Spring application context, runs a Flyway migration, and checks Java 21, the MySQL version, and a migrated record through JDBC. It does not implement product endpoints or domain behavior.

Run the full check again:

```powershell
.\scripts\Test-BackendTools.ps1 -IncludeSpringCheck
```

Logs and test results are under `.tools\verification`, including `maven-build.log`, `maven-verify.log`, and `stack-check\target\surefire-reports`. The disposable test database is separate from the persistent development database. Testcontainers cleans up its own containers after execution.

If the temporary project is deliberately removed, regenerate it from Spring Initializr with the listed dependencies and Java 21 before using `-IncludeSpringCheck`; basic environment checks do not require it.

## Docker startup repair performed during setup

The pre-existing Docker installation initially crashed because Windows could not remove orphaned AF_UNIX socket files (`dockerInference` and `engine.sock`). This matches a [reported Docker Desktop startup issue](https://github.com/docker/desktop-feedback/issues/460).

With Docker stopped, the runtime socket directories were preserved as timestamped backups and recreated. Docker containers, volumes, and settings were not reset. Preserved directories:

- `C:\Users\thesi\AppData\Local\Docker\run-xeats-backup-20261019T162419Z`
- `C:\Users\thesi\AppData\Local\Docker\run-xeats-backup-20261019T162539Z`
- `C:\Users\thesi\AppData\Local\docker-secrets-engine-xeats-backup-20261019T162539Z`

Prefer normal Docker shutdown over force-killing its processes. These preserved runtime files are not database backups and are not needed by Xeats.

## Download references

- [Eclipse Temurin Java 21](https://adoptium.net/temurin/releases/?version=21&os=windows&arch=x64)
- [Apache Maven downloads](https://maven.apache.org/download.cgi)
- [Official MySQL Docker image](https://hub.docker.com/_/mysql/)
- [Spring Initializr](https://start.spring.io/)

## Verification result

All initial setup checks passed on 19 October 2026:

- Java runtime/compiler: 21.0.12.1; Maven: 3.9.16.
- Temporary Spring Boot 4.1.1 project: Java 21 compilation and executable JAR packaging succeeded.
- `mvn.cmd -B -ntp '-Dmaven.repo.local=D:\Xeats\.tools\m2' verify` in the temporary project: **BUILD SUCCESS; 1 test, 0 failures, 0 errors, 0 skipped**.
- The integration test connected through Testcontainers 2.0.5 to a real disposable MySQL 8.4.11 container, applied one Flyway migration, initialized Hibernate/JPA, and read the migrated record through JDBC.
- Persistent `xeats-dev-mysql-1`: healthy; application user `xeats_app` authenticated to `xeats`; server timezone `+00:00`; host port `127.0.0.1:3307` accepted a connection.
- `Test-BackendTools.ps1`: passed, including application-user SQL execution and host-port reachability.
- PowerShell script parsing and Compose configuration validation: passed.
- The verification container and Testcontainers cleanup container exited; only the persistent development database was left running.

The downloaded MySQL image digest was `mysql@sha256:24989179f58856db763f418010c1169fecc944e3ec1ea7e921572d83eb070f70`. The Compose file currently selects the 8.4 maintenance line; deliberately pin/update the image as part of backend release configuration.

This establishes a working development toolchain. Product authorization, ordering, concurrency, and deployment checks still belong to their planned implementation chunks.
