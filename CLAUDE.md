# CLAUDE.md — Start Here

This file orients any AI model or agent (Claude, Claude Code, or otherwise) picking up work on **FitPilot** for the first time. Read this fully before touching code.

## What This Project Is

FitPilot is a full-stack AI-powered fitness tracking app: a Spring Boot REST API backend and a vanilla JS single-page frontend. Built solo, iteratively, over many sessions — see `PROGRESS.md` for exactly what's done and `PLAN.md` for what's left.

## Tech Stack

Versions and dependencies are in `backend/pom.xml`; hosting is in `render.yaml` and "If You're Deploying" below. Two things the code won't tell you: the frontend is vanilla HTML/CSS/JS as native ES modules — **no framework, no build step, no npm** — and the Gemini model is set by `GEMINI_MODEL`.

## Where Things Are

`backend/` is the Spring Boot (Maven) project and `frontend/` the static site. The docs live in the repo root and version with the code they describe: `ARCHITECTURE.md` (system design — read before structural changes), `AGENT.md` (conventions and gotchas — read before coding), `API.md` (every endpoint), `PROGRESS.md` (what's built), `PLAN.md` (what's left).

**If you change an endpoint, change its `API.md` entry in the same commit.** Swagger at `/swagger-ui/index.html` is for trying calls; it can't show the many `Map` responses.

## Running It Locally

**Backend** — needs a MySQL database (`CREATE DATABASE fitness_demo;` — empty is fine, Flyway builds it from `db/migration` on first start) and these env vars: `DB_URL`, `DB_USER`, `DB_PWD`, `JWT_SECRET` (any long random Base64 string), `GEMINI_API_KEY` and `MAIL_API_KEY` (both optional — the app degrades gracefully without either; with no mail key, password reset links are written to the log instead of emailed — see README > Password reset email for `MAIL_PROVIDER`/`MAIL_FROM`).

```bash
cd backend
./mvnw spring-boot:run
```
Runs on `localhost:8080`. Swagger at `/swagger-ui/index.html`.

**Frontend** — zero build step, but it has to be served over HTTP: the JS is native ES
modules, and browsers refuse to load those from `file://` (the page says so if you try).
```bash
cd frontend
python3 -m http.server 5500
```
Open `http://localhost:5500`. It auto-detects `localhost` vs a deployed origin to pick the right backend URL (see `js/config.js`).

**Tests:**
```bash
cd backend
./mvnw test   # uses H2 in-memory — no MySQL needed

cd frontend
node --test "tests/*.test.mjs"   # Node's built-in runner — no npm, no install
```

## Rules That Exist Because We Got Burned

These aren't style preferences — each one fixes a real bug that shipped and had to be debugged from a confusing symptom. Full detail and code examples are in `AGENT.md`. The headline version:

1. **Identity comes from the JWT, never from a path variable, query parameter or request body.** Use `CurrentUser.id()` / `CurrentUser.requireSelf(userId)`, and ownership-check anything loaded by resource id. This rule was documented long before it was true — it was implemented on exactly one endpoint while every other controller trusted a client-supplied `userId`, which meant any logged-in user could read or rewrite anyone else's data. Roles are assigned server-side; `RegisterRequest` has no `role` field.
2. **401 and 403 mean different things and the frontend depends on it.** 401 = no/expired/invalid token (the frontend silently refreshes and retries); 403 = valid token, not your data. Spring's stateless default returns 403 for both, which silently broke session renewal.
3. **Any service method that reads a `@ManyToOne`/`@OneToMany` field needs `@Transactional`.** Fields are lazy by default; without it you get `LazyInitializationException` that Spring wraps into a useless generic error at the controller boundary. Note the ownership checks in rule 1 read a lazy relationship, so they need this too.
4. **CORS is centralized in `SecurityConfig.corsConfigurationSource()`.** It reads `app.frontend-url` (env: `FRONTEND_URL`) in addition to localhost. Don't add ad-hoc `@CrossOrigin` annotations elsewhere.
5. **`server.port` must stay `${PORT:8080}`**, not hardcoded — Render assigns its own port at runtime.
6. **The schema belongs to Flyway, not to Hibernate.** `ddl-auto` is `validate`, so a mapping change with no matching `V<n>__` file fails at startup instead of the database being silently ALTERed underneath you. `ddl-auto=update` is what once left three foreign keys pointing at a dropped table, failing every activity insert for every user (`AGENT.md` §10). Vendor-specific SQL goes in `db/vendor/{vendor}` and never nested under `db/migration` — Flyway scans a location recursively, so a nested folder is handed to the H2-backed `FlywayMigrationTest` as well. The two locations share one version namespace, so a number is never reused across them.
7. **`GlobalExceptionHandler` is the only place that turns exceptions into HTTP responses.** Never let a raw `DataIntegrityViolationException` or Hibernate message reach the frontend — it's happened before (ugly, and a minor info leak). Only an exact `RuntimeException` becomes a 400; subclasses are real faults and become a logged 500 — unless they declare their own `@ExceptionHandler`, which is how `AccountNotFoundException` (a valid JWT whose user row is gone) returns the 409 the frontend self-heals from.
8. **Frontend never assumes a saved session is valid.** `js/api.js`'s `api()` wrapper self-heals on the specific "account no longer exists" 409 — see `AGENT.md` before changing this logic, it's had two rounds of bugs already.
9. **Don't use `TestRestTemplate` or `@AutoConfigureMockMvc`** in integration tests — both changed package/artifact location in Spring Boot 4.1 and broke the build twice. Integration tests here use plain `java.net.http.HttpClient` instead. Keep it that way.
10. **Don't reintroduce `spring-boot-starter-webflux`.** The two outbound HTTP calls (Gemini, and the password-reset email) both use `RestClient` from `spring-web`. WebClient meant shipping a second web framework and reactor-netty — 20MB of jars and its event-loop threads — to make a request that was immediately `.block()`ed.

## If You're Continuing Feature Work

Read `PLAN.md` first: it lists what's outstanding, in priority order, and every item cites the file and line that proves it. `PROGRESS.md` is the record of what was built; `PLAN.md` is the record of what of it actually works. Keep both current as you go.

## If You're Deploying

The stack is: **Render** (backend, free tier, deploys from `render.yaml`) + **Aiven** (MySQL, always-free tier) + **GitHub Pages** (frontend, from `/frontend`). Full steps aren't repeated here to avoid drift — check with whoever ran the deployment last, or re-derive from `ARCHITECTURE.md`'s deployment section.
