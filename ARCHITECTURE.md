# ARCHITECTURE.md — System Design

## Overview

FitPilot is a classic two-tier web app: a stateless REST API backend, and a static frontend that talks to it entirely over HTTP. There is no server-side rendering, no shared session state, no WebSocket layer — every interaction is a JSON request/response over the API.

```
┌─────────────────┐        HTTPS/JSON         ┌──────────────────────┐        JDBC        ┌──────────┐
│   Frontend       │ ────────────────────────► │  Backend               │ ──────────────────► │  MySQL    │
│   (static HTML/  │ ◄──────────────────────── │  Spring Boot REST API  │ ◄────────────────── │  (Aiven)  │
│    JS, no build) │      JWT in header         │  (Render)              │                      └──────────┘
└─────────────────┘                             └──────────┬────────────┘
                                                             │  HTTPS
                                                             ▼
                                                    ┌──────────────────┐
                                                    │  Gemini API       │
                                                    │ (flash-lite-latest)│
                                                    └──────────────────┘
```

Frontend and backend are deployed independently, on different platforms, and communicate only via the public API — this is deliberate, so either side can be redeployed, or even completely rewritten, without touching the other, as long as the API contract (`API.md`) holds.

## Backend architecture

Standard layered Spring Boot structure:

```
Controller  →  Service  →  Repository  →  Database
   ↑              ↑
 DTOs          Entities
```

- **Controllers** (`controller/`) parse HTTP requests into DTOs, call exactly one service method, and return the result. No business logic here.
- **Services** (`service/`) hold all business logic — calorie calculation, streak math, plateau/pattern detection, Gemini calls, goal progress updates, etc. Most write operations that touch more than one entity live here (e.g. logging an activity also updates streak, checks personal bests, updates muscle recovery, and updates goal progress — all orchestrated from `ActivityService.trackActivity()`).
- **Repositories** (`repository/`) are thin Spring Data JPA interfaces — query methods only, no logic.
- **Entities** (`model/`) map directly to MySQL tables via Hibernate. All relationships are `LAZY` by default (see `AGENT.md` §3 for why this matters).
- **DTOs** (`dto/`) are the request/response shapes — deliberately not 1:1 with entities (e.g. passwords never appear in any response DTO).
- **Security** (`security/`) — `JwtUtils` (token generation/validation), `JwtAuthenticationFilter` (reads the `Authorization` header, sets the Spring Security principal to the user id string), `SecurityConfig` (route authorization rules + CORS).
- **Cross-cutting config** (`config/`) — `RateLimitingFilter` (Bucket4j: auth, AI, invite and standard tiers, covering every `/api/**` route — `AGENT.md` has the table), `RestClientConfig` / `JacksonConfig` (explicit beans Spring Boot 4.1 no longer auto-registers).
- **`exceptions/GlobalExceptionHandler`** is the single point where any exception becomes an HTTP response — see `AGENT.md` §5.

## Auth flow

1. `POST /api/auth/register` — creates a `User` (bcrypt-hashed password), returns a `UserResponse` (no password field).
2. `POST /api/auth/login` — validates credentials, returns `{ accessToken, refreshToken, user }`. Access token: 48h JWT with the user id as subject and role as a claim. Refresh token: opaque UUID, stored server-side in the `RefreshToken` table with a 30-day expiry. Each login adds a row, so every signed-in device has its own refresh token.
3. Every subsequent request carries `Authorization: Bearer <accessToken>`. `JwtAuthenticationFilter` validates it and sets the authenticated principal to the user id — **controllers read identity from the JWT via `CurrentUser`, never from a client-supplied field** (see `AGENT.md` §2).
4. On 401 (expired access token), the frontend's `api()` wrapper calls `POST /api/auth/refresh` with the stored refresh token, gets a new token pair (refresh tokens rotate — the one presented is invalidated, and the user's other devices are untouched), and retries the original request transparently. A 401 that survives the retry means the session is genuinely over, and the wrapper sends the person back to the login screen.
5. `POST /api/auth/logout` revokes all of a user's refresh tokens server-side. The caller proves identity with an access token or the refresh token being revoked — it does not accept a user id. A password change and a password reset revoke them all too; the change hands the caller a fresh pair so its own device stays in.
6. Access tokens are checked against the account on every request. Each carries the account's `tokenVersion` (claim `tv`), and `JwtAuthenticationFilter` refuses one that no longer matches, which is a 401. A password change or reset adds one to the version, so every token issued before it dies at once. Logout does not, so a logged-out access token still lives out its 48 hours. A token with no claim counts as version 0, and an account that no longer exists is not refused here but left to the 409 path. The cost is one small query per authenticated request.

**Status codes are part of this contract:** 401 means "no/expired/invalid token" and drives the refresh above; 403 means "valid token, but not your data" and is terminal. Spring's stateless default returns 403 for both, so `SecurityConfig` sets an explicit authentication entry point — see `AGENT.md` §4.

## Database schema (grouped by feature)

| Group | Entities |
|---|---|
| Auth & identity | `User`, `UserRole` (enum), `RefreshToken` |
| Activity tracking | `Activity`, `ActivityType` (enum), `PersonalBest` |
| Muscle recovery | `MuscleRecovery`, `MuscleGroup` (enum) |
| Goals | `Goal`, `GoalType` (enum) |
| Pain tracking | `PainLog`, `BodyPart` (enum) |
| Challenges | `Challenge`, `ChallengeType` (enum), `ChallengeStatus` (enum), `ChallengeLeaderboardEntry` |
| Workout plans | `WorkoutPlan`, `WorkoutPlanDay`, `UserPlanEnrollment` |
| AI coaching | `Recommendation` |
| Health tracking | `BodyMetricsLog` (BMI history), `WaterLog`, `SleepLog` |

All entities use `@GeneratedValue(strategy = GenerationType.UUID)` string primary keys, not auto-increment longs — this was a deliberate early choice so IDs are safe to expose in the API and can't be enumerated.

`spring.jpa.hibernate.ddl-auto=update` — schema is generated/updated from entities automatically. There are no manual migration scripts. This is fine for a solo project at this scale; if this ever needs real migrations (multiple environments, rollback safety), replace with Flyway/Liquibase — see `PLAN.md` P1-2.

## Frontend architecture

Single-page app, but not built with a framework — deliberately, since the app is a solo full-stack learning/portfolio project and the API surface is the interesting part, not frontend tooling.

- **`index.html`** — all markup for every panel exists in the DOM at once. Navigation (`nav(panel)` in `js/nav.js`) just toggles which `<section class="panel">` has the `.on` class; nothing is dynamically inserted/removed from the DOM tree itself, only shown/hidden and re-populated.
- **`js/`** — native ES modules, loaded by the browser as they are (no bundler). Shared plumbing at the top level (`api.js`, `auth.js`, `nav.js`, `ui.js`, `actions.js`, `wake.js`, `state.js`, `config.js`, `charts.js`, `util.js`) and one module per panel in `js/panels/`. Each panel has a `load*()` function that fetches data and renders it into the panel's DOM, plus `handle*()` functions for form submissions. `main.js` is the entry point: it boots the session and maps every `data-action` / `data-submit` / `data-change` name in the markup to its handler; `actions.js` routes events to them through one delegated listener per event type. There are no inline `on*=` handlers and nothing on `window`, which is what lets `index.html` carry a Content-Security-Policy with no `'unsafe-inline'` for scripts. Tests live in `frontend/tests/`: unit tests run with `node --test` (no npm, also in CI), plus a Selenium regression suite in `tests/e2e/` that needs a running backend.
- **`api()`** is the single chokepoint for every backend call — auth header injection, silent token refresh, and session self-healing all live there (see `AGENT.md` §8). Nothing calls `fetch()` directly anywhere else.
- **Session state** lives in `localStorage` (`fp_accessToken`, `fp_refreshToken`, `fp_user`) plus the in-memory `session` object in `js/state.js`. On page load, the saved session is validated against the backend (`GET /api/profile/{id}`) before trusting it — catches the case where the database was reset after the browser already had a session saved.
- **No client-side router library** — panels are just show/hide, and `nav()` keeps the URL hash in step (`#goals`), so a panel survives a refresh, is bookmarkable, and works with Back/Forward.

## Key architectural decisions

| Decision | Why |
|---|---|
| MySQL over PostgreSQL | Established early, and Aiven's always-free tier happens to support MySQL directly, which avoided a migration when moving to deployment. |
| Vanilla JS over React/Vue | No build tooling to maintain, deploys as static files with zero pipeline, and keeps the project's learning focus on the backend + full-stack integration rather than frontend framework mechanics. |
| Monolith over microservices | Single developer, single deployable unit — microservices would add operational overhead with no corresponding benefit at this scale. |
| JWT over server sessions | Stateless backend, horizontally scalable in principle, and cleanly separable from the frontend's hosting (GitHub Pages can't hold server-side session state). |
| UUID primary keys over auto-increment | IDs are exposed directly in the API (in paths, and in friend and challenge payloads) — sequential integer IDs would leak user count and be guessable. |

## Deployment architecture

- **Backend** — Render, deployed from `backend/Dockerfile` via `render.yaml`. Free tier: spins down after 15 minutes of inactivity, ~30–60s cold start on the next request.
- **Database** — Aiven MySQL, always-free tier (1GB storage/RAM).
- **Frontend** — GitHub Pages, served directly from the `/frontend` folder on push to `main`.
- **CORS** — the backend's `FRONTEND_URL` env var must be set to the exact GitHub Pages origin for the deployed frontend to be allowed to call the deployed backend (see `SecurityConfig`).
- **CI** — GitHub Actions (`.github/workflows/ci.yml`) runs on every push touching `backend/**`: builds, runs the full test suite against H2, generates coverage, and (on `main` only) builds the Docker image and runs a real container smoke test against `/actuator/health`.
