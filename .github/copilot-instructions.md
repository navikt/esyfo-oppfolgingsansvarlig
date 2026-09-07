# esyfo-narmesteleder

This is the repository for narmesteleder-api, a service that provides an API for managing narmesteleder connection between employees on sick leave and their narmeste ledere.

## Team
- **Team**: team-esyfo, NAV IT
- **Org**: navikt

## Commands

```bash
./gradlew build   # Build + test + lint
./gradlew test    # Tests only
```

## NAV Principles
- **Team First**: Autonomous teams with circles of autonomy
- **Product Development**: Continuous development over ad hoc approaches
- **Essential Complexity**: Focus on essential, avoid accidental complexity
- **DORA Metrics**: Measure and improve team performance

## Platform & Auth
- **Platform**: NAIS (Kubernetes on GCP)
- **Auth**: Azure AD (internal users), TokenX (on-behalf-of token exchange), ID-porten (citizens), Maskinporten (machine-to-machine)
- **Observability**: Prometheus metrics, Grafana Loki logs, Tempo tracing (OpenTelemetry)

## Conventions
- English code and comments — Norwegian for user-facing text and domain terms (e.g. dialogmote, sykmelding, oppfolgingsplan)
- Use Context7 (`context7-resolve-library-id` → `context7-query-docs`) for library-specific patterns (not available for NAV-internal libs like Aksel/NAIS — use aksel.nav.no and doc.nais.io instead)
- Check existing code patterns in the repository before writing new code
- Follow the ✅ Always / ⚠️ Ask First / 🚫 Never boundaries in agent and instruction files

## Documentation and Working Notes

| Tier | Location | Purpose | Persists | Checked in |
|------|----------|---------|----------|------------|
| **Session** | `~/.copilot/session-state/` | Scratch work for one task | No | No |
| **Local notes** | `.local-notes/` | Plans, architecture drafts, research, AI reviews | Yes | No |
| **Permanent docs** | `docs/` | Finalized documentation (ADRs, API docs) | Yes | Yes |

**Defaults**: Planning/research/drafts → `.local-notes/`. Finalized docs → `docs/`. Task tracking → session state.

## Repository Instructions

This file documents the repository's conventions, commands, and boundaries. Update it directly when the technology stack, build and test commands, authentication, database, messaging, or operational practices change.

This file is maintained locally in this repository; it is not generated or synchronized from another repository. Do not assume that removed `.github/` agents, skills, instructions, or workflows still exist.


## Tech Stack
- **Language**: Kotlin
- **Framework**: Ktor
- **Build**: Gradle (Kotlin DSL)
- **Database**: PostgreSQL
- **Messaging**: Apache Kafka
- **Testing**: Kotest, MockK
- **Auth**: Les NAIS-manifestene i prosjektet for å finne hvilke auth-mekanismer som er konfigurert (mulige: Azure AD, TokenX, ID-porten, Maskinporten)

## Backend Patterns
- Check `build.gradle.kts` for actual dependencies before suggesting libraries
- Use Flyway for all database migrations — never modify existing migrations
- Parameterized queries always — never string interpolation in SQL
- Follow the existing data access pattern in the repository (extension functions, repositories, etc.)
- Structured logging — check which pattern this repo uses (KotlinLogging, SLF4J, kv() fields, MDC)
- Follow existing code patterns in the repository

## Boundaries

### ✅ Always
- Run `./gradlew build` after changes
- Use Flyway for database migrations
- Add Prometheus metrics for business operations
- Validate JWT issuer, audience, and expiration

### ⚠️ Ask First
- Changing database schema or Kafka event schemas
- Modifying authentication configuration
- Adding new GCP resources

### 🚫 Never
- Skip database migration versioning
- Hardcode secrets or configuration values
- Use `!!` operator without null checks
- Bypass authentication checks
