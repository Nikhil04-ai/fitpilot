# PROGRESS.md — What's Built

Phase-by-phase record of what exists in FitPilot today. `PLAN.md` holds what's left.

> **Note on this file:** `CLAUDE.md` referenced a `PROGRESS.md` that wasn't actually
> present in the repo (nor were `PLAN.md` or `API.md`). This file was reconstructed
> from the codebase itself — the feature and phase numbering below is taken from the
> `Feature N —` / `Phase N —` markers in the source, so it reflects what is genuinely
> implemented rather than what was once planned. The hardening pass at the end is a
> first-hand record. (`PLAN.md` and `API.md` both exist now.)
>
> **Audit and repair, 2026-09-21:** a line-by-line audit found that several rows below
> marked "Done" were only partly wired — the backend existed but nothing reached it, or
> the frontend read a field the endpoint never returned. **All seven findings have since
> been fixed** (see `PLAN.md`, P0), and the suite went from 126 to **190 passing tests**.
> The affected rows below say what was wrong and what closed it.
>
> **Foundation work, 2026-09-22:** the whole **P1** section of `PLAN.md` is done —
> Flyway, charts, exercise-level logging, tiered rate limiting and password reset. See
> Phase 8 at the bottom. **287 passing tests.**

---

## Phase 1 — Core domain & auth

| | Status |
|---|---|
| `User` entity, bcrypt password hashing, UUID primary keys | Done |
| `POST /api/auth/register`, `POST /api/auth/login` | Done |
| JWT access tokens (48h), `JwtUtils` + `JwtAuthenticationFilter` | Done |
| `SecurityConfig` — route rules, stateless sessions, CORS | Done |
| `Activity` entity + `POST`/`GET /api/activities` | Done |
| `GlobalExceptionHandler` as the single exception→HTTP funnel | Done |

## Phase 2 — The fourteen features

| # | Feature | Where |
|---|---|---|
| 1 | Smart calorie auto-calculator (MET-based, uses body weight) | `CalorieCalculatorService` |
| 2 | Activity streak system (current + longest) | `StreakService` |
| 3 | Personal best tracker — PR badge **fixed** (P0-3), message names what broke (P0-6), and since P1-1 it tracks **distance** too, with per-movement strength records (heaviest set, estimated 1RM, session volume) alongside | `PersonalBestService`, `ExercisePersonalBestService` |
| 4 | Muscle recovery tracker (per muscle group, overtraining warnings) | `MuscleRecoveryService` |
| 5 | Workout timing analysis (peak performance hour) — now also returns the full 24-hour distribution, drawn as a histogram (P1-3) | `TimingAnalysisService` |
| 6 | Pain point logger + injury pattern detection (3+ reports in 21 days) | `PainAnalysisService` |
| 7 | Plateau detector | `PlateauDetectorService` |
| 8 | Comeback protocol (reduced intensity after a 14+ day gap) | `ComebackService` |
| 9 | Natural language activity logging — parsed values are **displayed** (P0-2) and now **acted on** (P1-1): distance is a real column feeding stats and records, and the prompt extracts a list of movements rather than two loose sets/reps numbers | `NaturalLanguageActivityService` |
| 10 | AI coaching recommendations, auto-triggered every 3rd activity | `RecommendationService` |
| 11 | Weekly / monthly / all-time stats | `StatsService` |
| 12 | Goal setting + automatic progress tracking — deadlines are now **enforced** (P0-7): an expired goal stops accruing, is badged in the UI and drops off the overview | `GoalService` |
| 13 | Workout difficulty rating + progression analysis — **wired** (P0-1), and since P1-1 driven by **measured load** where any exists: each movement's estimated 1RM across its last three sessions. The 1–5 rating is the fallback for cardio | `ActivityService`, `ProgressionService` |
| 14 | 1-on-1 challenges: invite → accept → nightly scoring → winner | `ChallengeService` |

Plus health tracking: BMI history (`BodyMetricsService`), hydration (`WaterIntakeService`),
sleep with workout correlation (`SleepService`), and structured workout plans with
enrolment and daily progress (`WorkoutPlanService`, four seeded system plans).

## Phase 3 — Frontend

| | Status |
|---|---|
| Single-page vanilla JS client, 12 panels + auth screen, no build step | Done |
| `api()` wrapper — auth header, silent refresh, session self-healing | Done |
| Every feature above surfaced in the UI | Done — was **not** true until 2026-09-21 (rating had no UI, metrics were never displayed, the PR badge couldn't render). All three fixed; see `PLAN.md` P0 |
| `API_BASE` auto-detects local vs deployed | Done |
| Charts / any data visualisation | Not started — zero `<canvas>` in the whole frontend (PLAN P1-3) |

## Phase 4 — Production concerns

| | Status |
|---|---|
| Refresh tokens (30d, opaque UUID, rotated on use, revocable) | Done |
| Rate limiting (Bucket4j, 100 req/min on `/api/activities`) | Done |
| Pagination on activity listing | Done |
| Swagger / OpenAPI docs | Done |
| Actuator health endpoint | Done |
| JUnit 5 + Mockito unit tests, H2 integration tests | Done |
| GitHub Actions CI — build, test, coverage, Docker | Done |

## Phase 5 — Deployment

| | Status |
|---|---|
| `Dockerfile` (multi-stage, non-root user) | Done |
| `render.yaml` blueprint | Done |
| Aiven MySQL + GitHub Pages frontend | Done |
| `FRONTEND_URL` / `PORT` driven by environment | Done |

---

## Phase 6 — Security & correctness hardening

A full audit of the backend, frontend and CI, done in two passes. Everything below is
implemented and covered by tests unless noted otherwise.

### 6a — Authorization (the big one)

The rule "identity comes from the JWT" had been documented since early on, but was
actually implemented on **exactly one endpoint**. Every other controller took a
`userId` from a path variable, query parameter or request body and acted on it with no
check — so any logged-in user could read or modify any other user's profile,
activities, goals, stats, health logs, pain logs, plans, recommendations and
challenges by changing a UUID.

| Fix | Detail |
|---|---|
| `security/CurrentUser` helper | `id()`, `idOrNull()`, `requireSelf()`, `requireOwner()` — the only way to answer "who is calling" |
| All 12 controllers converted | `{userId}` path segments verified against the caller (403 on mismatch); body `userId` ignored |
| Ownership checks in services | `rateActivity`, `addNotes`, `updateGoal`, `deleteGoal`, activity-scoped recommendations, challenge detail + leaderboard |
| Role escalation closed | `RegisterRequest.role` removed — it was written straight to the saved user, so `{"role":"ADMIN"}` self-provisioned an admin |
| Logout secured | No longer accepts a bare `userId`; the caller proves identity with an access or refresh token |
| `hasRole("ADMIN")` made functional | The JWT filter granted a bare `ADMIN`; `hasRole` needs `ROLE_ADMIN`, so the rule could never match |

URLs were left unchanged throughout, so the frontend and `API.md` remain valid.

### 6b — Auth status codes

Spring's stateless default returned **403** for a missing or expired token, but the
frontend only attempts its silent token refresh on **401** — so once a 48h access
token expired, every panel showed an error instead of renewing. Added an explicit
`authenticationEntryPoint` (401) and `accessDeniedHandler` (403), plus an
`AccessDeniedException` handler in `GlobalExceptionHandler` (one thrown inside a
controller never reaches Spring Security's filter, so it was being reported as a 400).
`app.js` now forces a clean re-login when a 401 survives a refresh attempt.

### 6c — Build and CI (both were broken as committed)

| Fix | Detail |
|---|---|
| Maven wrapper added | `mvnw`, `mvnw.cmd`, `.mvn/wrapper/` — the `Dockerfile`, CI and every doc called `./mvnw`, which did not exist |
| CI Docker smoke test repaired | It ran the container against an H2 JDBC URL, but `h2` is `test`-scoped and absent from the runtime image, so the container could never start. Now brings up a real MySQL on a shared docker network |
| Smoke test strengthened | Also asserts an unauthenticated `/api/activities` returns 401; teardown runs with `if: always()` |

### 6d — Correctness

| Fix | Detail |
|---|---|
| Timezone bug | The frontend sent `new Date().toISOString()` into a `LocalDateTime`; Jackson silently dropped the `Z`, storing every activity UTC-shifted. Surfaced as "peak workout time" being wrong by exactly the UTC offset. Now `localDateTimeNow()` |
| Weekly goals reset | Progress accumulated forever — "5 workouts per week" stayed complete at 5/5 permanently. `Goal.periodStart` tracks the week and rolls over on Monday |
| Live challenge scores | Scores were only written by the 23:55 job, so the UI showed yesterday's numbers all day. Active challenges recompute on read |
| Gemini modernized | `gemini-1.5-flash` is retired (every AI call was silently falling back). Model now configurable, key moved to the `x-goog-api-key` header, timeouts added, empty-candidates responses handled instead of NPE-ing |
| Exception handling | Blanket `RuntimeException → 400` reported genuine faults as client errors with internal messages. Now: exact `RuntimeException` → 400, subclasses → logged 500, framework exceptions keep their own status (a URL typo was going to return 500) |
| Jackson strictness | The custom `ObjectMapper` bean re-enabled `FAIL_ON_UNKNOWN_PROPERTIES`, so any extra request field was a hard 400 |
| Plan double-completion | Repeated "mark complete" clicks burned through a 7-day plan in one sitting |
| Null-safety | `MuscleRecoveryService` NPE'd on a null `lastWorkedAt`; activities now default a null `startTime` (stats, scores and timing analysis all filter on it and were skipping those rows) |
| Email handling | Stored and matched case-insensitively, with a readable duplicate message instead of a generic constraint violation |

### 6e — Performance

| Fix | Detail |
|---|---|
| Per-write full scans removed | `RecommendationService` loaded a user's entire activity history on **every save** to call `.size()`, then loaded it again to sort for the last 5. Now `countByUserId` + `findTop5ByUserIdOrderByStartTimeDesc` |
| All-time stats aggregated in SQL | Was summing two columns in the JVM over every activity ever logged |
| Rate limiter rewritten | Keyed on the access token, which rotates — every refresh handed out a fresh full bucket, so the limit was trivially resettable, and the bucket map only ever grew. Now keyed on the JWT subject with idle eviction and a hard ceiling |
| Webflux dropped | `GeminiService` migrated from `WebClient` to `RestClient`; removed `spring-boot-starter-webflux` and the reactor-netty stack — **20.2 MB across 35 jars**, plus its event-loop threads. It was there to make one request that was immediately `.block()`ed |
| SQL logging off by default | `show-sql=true` was logging every statement in production; now `SHOW_SQL=true` opt-in |

### 6f — Tests

**83 runnable → 126 passing.** New coverage:

- `CurrentUserTest` — the authorization primitives
- `JwtAuthenticationFilterTest` — principal extraction and the `ROLE_` prefix
- `ActivityServiceAuthorizationTest`, `GoalServiceAuthorizationTest` — cross-user access denied
- `UserServiceTest` — role escalation, duplicate email, case-insensitive login
- `RateLimitingFilterTest` — bucket keying, 429 behaviour, preflight bypass
- `GeminiServiceTest` — 9 tests via `MockRestServiceServer` (the service had none)
- `GoalServiceTest` — weekly roll-over cases
- `ActivityControllerIntegrationTest` — a cross-user authorization block, and its
  `security_noJwt_returns403` assertion corrected to 401

`FitnessMonolithApplicationTests` switched from `RANDOM_PORT` to `MOCK`: it exists to
prove the context wires up, not to serve HTTP (the integration tests do that), and
starting a real Tomcat only made it unrunnable where the server can't bind.

### Known environment issue (not a code defect)

On the current Windows dev machine, `Selector.open()` fails with
`Unable to establish loopback connection` — the JDK's NIO self-pipe connects over
AF_UNIX and something is blocking it. Plain sockets work, so it isn't obvious. The
consequence is that **nothing needing NIO runs locally**: `java.net.http.HttpClient`,
embedded Tomcat, both `*IntegrationTest` classes, and `./mvnw spring-boot:run`. Usual
fix is `netsh winsock reset` plus a reboot. CI (Linux) is unaffected — the integration
tests compile and run there. See `AGENT.md` §7.

---

## Phase 7 — P0 repair (2026-09-21)

The audit in `PLAN.md` found seven shipped-but-broken features. All are now closed.

| Fix | Detail |
|---|---|
| Feature 13 actually wired | `ProgressionService` had **zero callers** and `difficultyRating` appeared nowhere in `app.js`. The rating is now a clickable 1–5 control on every row, `analyseProgression` runs on rate, and its suggestion comes back as `ActivityResponse.progressionSuggestion` |
| Parsed metrics displayed | Distance/sets/reps from the NL parser were saved and returned but never rendered. `metricChips()` shows them, with labels per key |
| Activity list returns the DTO | `GET /api/activities` handed back the raw `Activity` entity, so the frontend's `isPersonalBest` badge was reading a field that does not exist on it. Now `Page<ActivityResponse>`, with the current record-holder marked |
| Edit and delete | `PUT` and `DELETE /api/activities/{id}`, both ownership-checked, plus an edit modal and per-row controls |
| Derived state recalculation | New `ActivityRecalculationService`. Streaks, personal bests, muscle recovery and goal progress are rebuilt from the activity rows after an edit or delete — none of them can be undone incrementally, so deleting a record-setting workout used to leave the record standing |
| `completedAt` populated | Was null on every activity ever logged; now `startTime + duration` |
| Personal-best message | Was hardcoded to "longest session" even when the calorie record was the one that broke |
| Goal deadlines enforced | Stored and never compared against today, so a goal missed months ago still read as active. Expired goals stop accruing, skip the Monday roll-over, and are surfaced as expired |

**Browser-verified 2026-09-21.** The frontend half was driven in real Chrome against
the app on the H2 profile — login through the form, then **15/15 checks**: metric chips
render (`5 km`/`3 sets`/`12 reps`), the PR badge appears, clicking a rating star
persists it, the edit modal prefills and saves, delete removes the row, and expired
goals show the badge and "never reached". Screenshots confirmed visually.

**Tests: 126 → 190 passing.** New: `ActivityRecalculationServiceTest` (9), edit/delete
authorization cases, goal-deadline cases, personal-best message cases, and 6 integration
tests covering the new endpoints and response shapes.

---

## Phase 8 — P1 foundation (2026-09-22)

The five `PLAN.md` P1 items, in the order the plan called for. Full detail lives there;
this is the record of what exists now.

| | What landed |
|---|---|
| **P1-2 · Flyway** | `db/migration` owns the schema and `ddl-auto` is `validate`. V1 is a baseline generated from the entity mappings themselves, so existing databases are stamped rather than rebuilt. `FlywayMigrationTest` runs the migrations on an isolated H2 and makes Hibernate validate against them — a mapping change with no migration now fails the build |
| **P1-3 · Charts** | Four, Chart.js from a CDN, no build step: 30-day training volume, hour-of-day histogram, weight/BMI trend, sleep-vs-next-day-output scatter with a Pearson correlation. Two reuse payloads the API already returned; two added `GET /api/stats/daily` and `GET /api/health/sleep/{id}/performance` |
| **P1-1 · Exercise logging** | `Exercise` under `Activity` (name, sets, reps, weight), with volume and an Epley 1RM estimate. `ExercisePersonalBest` per movement, rebuilt after edits and deletes. `distanceKm` promoted from the JSON blob to a real column, backfilled by a MySQL-only migration. Bodyweight movements record reps, since they have no load to compare |
| **P1-5 · Rate limiting** | Three tiers instead of one path: auth (10/min, **always IP-keyed**), AI (10/min), standard (100/min). `/api/auth/login` and `/api/auth/register` were previously uncapped |
| **P1-4 · Password reset** | `forgot-password` / `reset-password`, single-use hashed tokens with a 30-minute life, all sessions revoked on use, and no account enumeration. Email goes over HTTPS rather than SMTP because Render blocks SMTP on the free tier |

### Fixed in passing

`FRONTEND_URL` — set by `render.yaml` and documented in `SecurityConfig` — was never
reaching the `app.frontend-url` property that reads it, because Spring maps that
variable to `frontend.url`. The deployed frontend origin was therefore silently absent
from the CORS allow-list. One line in `application.properties` binds them; the password
reset link depends on the same property, which is how it surfaced.

### Verification

**Tests: 190 → 287 passing**, all green. New suites: `FlywayMigrationTest`,
`ExerciseTest`, `ExercisePersonalBestServiceTest`, `ProgressionServiceTest`,
`StatsServiceDailySeriesTest`, `SleepPerformanceTest`, `TimingAnalysisServiceTest`,
`PasswordResetServiceTest`, plus `ExerciseLoggingIntegrationTest` and
`PasswordResetIntegrationTest`, and nine new cases on `RateLimitingFilterTest`.

**Browser-verified in real Chrome**, against the app running on the H2 profile with
Flyway migrating and `ddl-auto=validate`:

* **21/21** on the charts — series lengths, axis choices, the note under each chart,
  and that re-navigating panels does not trip Chart.js re-use errors
* **21/21** on exercise logging — movements and 1RM estimates on the row, PR badges,
  distance promoted from the legacy blob, the builder dropping blank rows, the edit
  modal prefilling, and both record sections
* **13/13** on password reset — the whole journey from "forgot" through the emailed
  link to logging in with the new password, plus the old password failing, the token
  being scrubbed from the URL, and a replayed link being refused

---

## Phase 9 — P2 features (2026-09-25)

All five `PLAN.md` P2 items, in the order the plan recommended: nutrition first because
it unblocked a whole category of goal, then the cheap export, then the AI digest (which
wanted nutrition to already exist), then the delivery layer, then group challenges.

| | What landed |
|---|---|
| **P2-1 · Nutrition** | `MealLog` + `NutritionService` + `/api/nutrition`. Manual and free-text logging (Gemini estimates portions, flagged `aiEstimated`), daily summary with intake / training burn / estimated maintenance / net, 30-day energy-balance chart. `CalorieCalculatorService` gained sex-neutral Mifflin-St Jeor. `GoalType` gained `CALORIE_INTAKE_PER_DAY`, `PROTEIN_PER_DAY`, `TARGET_WEIGHT`, which needed `GoalPeriod` (daily/weekly/none) and `GoalDirection` (at-least/at-most/towards) |
| **P2-5 · Data export** | `/api/export` — full JSON, one CSV per dataset, or a zip of all ten. Streams to the servlet response rather than buffering. CSV formula injection neutralised; the password hash never leaves |
| **P2-3 · Weekly digest** | `WeeklyDigest` + `/api/digest`. Gathers training, per-movement 1RM trends, nutrition, sleep, body weight, pain and goals into one fact set, then asks Gemini to interpret it. Scheduled Monday 08:00 for active users only |
| **P2-2 · Delivery layer** | `Notification` + `/api/notifications`, bell with unread badge, feed panel. Producers: the digest, challenge settlement, a daily goal-deadline job, and the plateau scan — which **was a stub** (a log line and a TODO) and now actually runs |
| **P2-4 · Friends + groups** | `Friendship` + `/api/friends` (add by email, no membership leak). `ChallengeParticipant` replaces the `creator`/`opponent` and `creatorScore`/`opponentScore` pairs, so a challenge holds up to 10 people. V8 migrates existing 1-v-1 rows into it and drops the old columns |

### Decisions worth keeping

* **Nutrition subtracts maintenance, not just training burn.** "Eaten minus exercise"
  is not a deficit — it ignores the ~1900 kcal a body spends existing, so every honest
  day of eating read as a large surplus. The summary returns the components separately
  because two of the three are estimates.
* **A day with no meals logged is a gap in the chart, not a zero.** Unlike the training
  volume chart, where a rest day genuinely burned nothing.
* **A calorie budget is never "complete".** `GoalDirection.AT_MOST` reports
  `remaining` / `exceededBy` instead, because a tick next to having barely eaten is the
  wrong message.
* **A body-weight goal measures from where you started.** 76kg between 80 and 72 is
  half done; as a plain current/target ratio it is 105% and meaningless. Hence
  `Goal.startValue`.
* **The digest computes the facts and only asks the model to interpret them.**
  `dataPoints` stores the figures next to the prose, so a hallucinated number has
  nowhere to hide. With no API key the rule-based fallback is still a real digest.
* **Notifications never throw into their producer.** Losing an announcement is bad;
  losing the challenge result it was announcing is worse.
* **An unanswered challenge invite is not a score of zero.** Only accepted participants
  are ranked, and a challenge nobody joined is cancelled rather than won by default.

### Verification

**Tests: 287 → 400 passing** as of the P2-2 full-suite run. P2-4 then rewrote
`ChallengeServiceTest` (9 → 21) and added `FriendshipServiceTest` (14) and
`GroupChallengeIntegrationTest` (11). **Full suite re-run 2026-09-25 after P2-4: 437
passing, 0 failures, 0 errors** (400 + 12 + 14 + 11).

New suites across the phase: `NutritionServiceTest`, `GoalServiceNutritionTest`,
`ExportServiceTest`, `WeeklyDigestServiceTest`, `NotificationServiceTest`,
`FriendshipServiceTest`, plus `NutritionIntegrationTest`, `ExportIntegrationTest`,
`WeeklyDigestIntegrationTest`, `NotificationIntegrationTest` and
`GroupChallengeIntegrationTest`.

Migrations V5 (nutrition), V6 (weekly digest), V7 (notifications) and V8 (friends +
group challenges) all apply on H2 under `FlywayMigrationTest`, including V8's
`INSERT..SELECT` data migration and its column drops.

### Live verification and fixes (2026-09-25)

Run against **real MySQL** on both paths Flyway will ever take: an empty database (V1→V8)
and a copy of the pre-Flyway `fitness_demo` (baselined at V1, then V2→V8, seeded with
ACTIVE / PENDING / COMPLETED 1-v-1 challenges). V8 moved every old challenge across
correctly, and pre-V8 leaderboard snapshots (`standings` NULL) still render. ~95 API
checks across all five features, including every cross-user access attempt.

Five bugs surfaced that the unit/integration suite could not see, all fixed:

| | Bug | Fix |
|---|---|---|
| 1 | `gemini-2.0-flash` retired → **every AI feature silently on its fallback**; users told to "set GEMINI_API_KEY" when it was set; `GEMINI_MODEL` / `GEMINI_TIMEOUT_SECONDS` never bound | Default `gemini-flash-lite-latest` (an alias, ~2s — full Flash took ~45s against the 20s timeout); both env vars bound in `application.properties`; messages distinguish "no key" from "call failed" |
| 2 | Group challenge ACTIVE while you're still INVITED was listed twice, with a Leave button; Leave after declining answered "you left" again | List shows only challenges you're `ACCEPTED` in; `leaveChallenge` refuses INVITED/DECLINED |
| 3 | Bell dot showed "0" forever (`.bell-dot{display:flex}` beat `[hidden]`); count only refreshed on login | `.bell-dot[hidden]{display:none}`; polls every 60s while visible + on tab refocus |
| 4 | Meal edit resent every number → renaming an AI meal cleared its "~ est" badge; emptying a field silently kept the old value | Flag clears only when a number actually changes; `MealRequest.clear` names fields to empty |
| 5 | Edit-goal input had no `step`, so 72.5 kg couldn't be saved | `step="any"` |

**Tests: 437 → 443 passing.**

**Browser-verified in real Chrome** (Selenium, headless, against MySQL, default model
with no override): **34 checks, 32 passing first time** — login, bell count / hide / 60s poll, challenge invites vs
list, 3-way standings and leader, decline, add-friend by email, nutrition summary /
"~ est" badge / macros / chart, both meal-edit fixes, over-budget goal in red, weight
goal 50% from its start, decimal goal edit, AI-written digest, notification deep-link,
mark-all-read, JSON and zip downloads, no console errors. Both misses were the test's own
errors: a colour assertion too strict (the screenshot shows the over-budget line red) and
a seed step that created no notification; a focused re-run of the 60s poll passed 2/2.
Screenshots reviewed.

The Friends card's email input used the browser's default styling instead of the dark
theme. Fixed in Phase 10.

## Phase 10 — P3 polish (2026-09-25)

| | What landed |
|---|---|
| **P3-3 · Pagination** | `GET /api/pain-log/{userId}` and `GET /api/health/sleep/{userId}/history` are paged (`page`/`size`, newest first). Body-metrics history has its own paged `GET /api/health/metrics/{userId}/history`. `/metrics` still embeds the trend for the chart, but only the latest 100 weigh-ins (oldest first), plus `historyTotal`. The Pain Log panel got the shared Prev / "n of m" / Next pager. A negative `page` is now 0 on **every** paged endpoint; activities, meals and notifications used to return 500. |
| **P3-2 · `API.md`** | All 83 operations in 18 groups, plus a conventions section: auth and token lifetimes, the identity rule, what each status code means here, the two error shapes, pagination, rate-limit tiers and AI fallbacks. Each shape was traced from controller through service to the object Jackson actually serializes. |
| **Style** | `.export-row` inputs and selects (the add-friend box and the export picker) now match the dark theme. |

### Decisions worth keeping

* **`/metrics` keeps an embedded history rather than dropping it.** The weight chart draws
  from the same response, so no second request. The embedded history is capped, and
  `historyTotal` says when the record goes further back.
* **`API.md` documents what the code does, not what it should do.** Where the two differ,
  the entry says so plainly: the 500 on a missing activity `type`, the calories overwritten
  on an activity edit. Each gap like that is now a `PLAN.md` P4 item. The doc is only trustworthy
  if nobody has to wonder whether an entry is aspirational.

### Verification

* **Tests: 443 → 451 passing.** `HistoryPaginationIntegrationTest` covers:
  - page sizes and ordering
  - the 100-cap on `/metrics` and that the *old* end is what's dropped
  - clamping, including a negative `page` on all six paged endpoints
  - ownership: another user gets `403`
* **`API.md` against the code:** its route list matches the live OpenAPI spec exactly
  (83/83, nothing missing or extra). 28 specific claims were checked against a running
  server on MySQL, and all matched: status codes, message text and response keys.
* **Chrome (Selenium), 11/11:**
  - the email input and export picker are themed
  - Pain Log shows 15 per page, newest first, with "1 of 2", Next, Prev, and Next
    disabled on the last page
  - a new entry appears at the top of page 1
  - the weight chart still renders from the capped history
  - no console errors

  Screenshots reviewed.

---

## Phase 11 — Frontend split into ES modules (2026-09-25)

`frontend/app.js` (3,607 lines) is now 23 native ES modules under `frontend/js/`: shared
plumbing at the top level (`api`, `auth`, `nav`, `ui`, `state`, `config`, `charts`, `util`)
and one module per panel in `js/panels/`. `index.html` loads `js/main.js` with
`<script type="module">`. There is still no bundler, no build step and no npm.

The code was moved, not rewritten: a script cut `app.js` by line range, and it checked
that every non-blank line landed in exactly one module. The only edits are these:

* **Session state lives on one object.** `accessToken`, `refreshToken`, `currentUser` and
  `sessionExpiredInProgress` are `session.*` in `js/state.js`. Several modules reassign
  them, and an imported binding is read-only.
* **Inline handlers are listed on `window`.** Module scope is not global. The 56 functions
  that `onclick=`/`onsubmit=`/`onchange=` call (from `index.html` and from rows built
  with `innerHTML`) are put on `window` in one list at the bottom of `js/main.js`.
  AGENT.md §8 explains how to keep the list up to date.
* **`file://` no longer works.** Browsers won't load modules from it. Opening
  `index.html` directly now shows a message saying to run `python -m http.server`,
  instead of a login form that does nothing.

### Verification

* **ESLint** (`no-undef`, `no-import-assign`, `no-unused-vars`, run from a scratch folder
  so nothing was added to the repo): clean. One unused variable in
  `generateRecommendationNow` was already there before the split.
* **Chrome (Selenium), 38 checks, run on the old `app.js` first as a baseline:**
  - all 56 handlers are reachable on `window`
  - register → auto-login
  - all 14 panels open from the sidebar with no error states
  - the log-workout modal: exercise builder, focus trap, Escape
  - activities: rate, edit and delete; the pager
  - goals: create, edit, delete
  - pain log, water, plateau check, plan enroll and complete, profile save
  - deep link and Back after a real reload
  - silent token refresh at boot, logout, dead-session recovery
  - no console errors, and the `file://` message

  The old frontend passed 37/37 (the `file://` check doesn't apply to it). The modules
  passed 38/38, from staging and again from `frontend/` itself.
* **Phase 9 browser suite:** 32/34 on the modules, and the same 32/34 with the same two
  failures on the old `app.js`, so the split didn't cause them:
  - one check looks for `rgb(`, but Chrome reports `rgba(…)`
  - the bell never picked up the challenge that check seeds. The polling itself works: a
    separate check using a friend request instead passed in 58s. Why that seeded
    challenge raises no notification has not been investigated.

### Fixes after the split (2026-09-25)

* **Stored XSS in toasts (security).** `toast()` set `innerHTML`, and server messages go
  straight into it. Friend acceptance returns "You're now friends with <their name>",
  and `firstName` accepts any string. So a name like `<img src=x onerror=…>` ran as
  script in the browser of whoever accepted the request, where it could read their
  tokens from `localStorage`. This was reproduced in Chrome (the handler fired) and is
  fixed with `textContent`. Two smaller sinks got `escapeHtml()` as well: the overview
  avatar initials and the AI card's fallback label. Backend validation of names has
  **not** been added.
* **Login/register error wording.** They said "Is the backend running on localhost:8080?"
  on the deployed site too, and showed `Unexpected token '<'` when the server answered
  with an HTML error page. They now share `unreachableMessage()` with `api()` and fall
  back to the HTTP status when the body isn't JSON.
* **Avatar "MUNDEFINED".** An empty last name (optional at registration) made
  `(last||'')[0]` undefined. Fixed with `initials()` in `js/util.js`.
* **Contrast.** `--muted` went from `#4A5568` (about 2.1–2.6:1) to `#808C9F`, which is at
  least 4.5:1 on all four background surfaces.
* **Chart.js SRI.** The tag now carries a `sha384` integrity hash. It loads
  `dist/chart.umd.js`, a file that is in the npm package: its sha256 matches jsDelivr's
  package listing. `chart.umd.min.js` is not in the 4.4.3 package; jsDelivr generates
  it and warns against putting SRI on it.

**Verification:** a targeted Chrome check scored 3/14 on the pre-fix build, reproducing
every bug above, and 14/14 after the fixes. The 38-check regression run is still 38/38.
ESLint is clean.

### Follow-up: password reset email (2026-09-25)

**"Forgot password doesn't work."** The reset flow itself was fine. Driven in Chrome it
passes 10/10: link, new-password form, new password saves, old one refused, login with
the new one, and the same link can't be used twice. **The problem was that no email
provider was configured anywhere.** Each reset link went to the IntelliJ console, while
the page correctly said "a reset link is on its way", so it looked like nothing happened.
The database showed two real requests, each with a valid link that was never used.

| Change | Why |
|---|---|
| **Brevo** added as a provider next to Resend (`MAIL_PROVIDER`, default `brevo`) | Resend without a domain only delivers to its own account's address. Brevo delivers to anyone once the sender address is verified. |
| **One startup line**: `Email is OFF` / `ON via …` / `misconfigured: …` | The reset UI must report success either way, so a disabled transport was indistinguishable from a working one. |
| **Misconfiguration caught before sending**: a key with no `MAIL_FROM`, Brevo with Resend's test sender, an unknown provider | Each of those is otherwise a silent refusal at the provider. |
| **The link is logged only when email is off** | If a configured provider failed, the old code logged a live reset link, a password equivalent, into production logs. |

Tests: `EmailServiceTest` (new, 9) checks the exact Brevo and Resend request shapes
against a mock server, and `PasswordResetServiceTest` gained the configured-but-failed
case.

**Live delivery confirmed (2026-10-07).** With a Brevo account, a verified sender, and
`MAIL_API_KEY`/`MAIL_FROM` set in `backend/local.properties`, the backend logs
`Email is ON via Brevo` at startup and a real reset email arrived. Locally, forgot
password now works end to end. The deployed backend still needs the same three `MAIL_*`
variables set in Render.

---

## Phase 12 — P4 fixes, first four (2026-10-07)

`PLAN.md` P4-1 to P4-4, the bugs and security problems found while writing `API.md`.
P4-11 closed along with P4-4. Each fix started with a test that failed on the old code.

| | Fix | Frontend half |
|---|---|---|
| **P4-1** | A dead session exporting CSV or zip got an empty `200`, because the headers and BOM went out before the account lookup. `ExportService.requireAccount()` now runs before any header. | `api()`'s `raw` branch threw before the dead-session check, so even a correct `409` only showed a toast. Failed downloads now share the normal error path. |
| **P4-2** | An activity edit that omitted calories recalculated over the typed figure, and nulled it with no profile weight. Now an omitted figure is kept, unless type or duration changed and the figure was itself calculated. A new `recalculateCalories` flag re-derives on request. | The edit modal sent the pre-filled figure back every time, which pinned it as typed. It now sends a figure only when one was changed, and asks for a recalculation when the field is blank. |
| **P4-3** | Issuing a refresh token revoked all the others, on every refresh as well as every login, so one tab renewing signed out every device. Tokens are now per device, and rotation retires only the token presented, through an atomic conditional `UPDATE`. A password change now revokes every session and returns a fresh pair. A daily job purges spent tokens. | The profile panel stores the pair a password change returns (`storeTokens()` in `api.js`). |
| **P4-4** | **Challenges are friends-only** (the user's decision). A non-friend and a non-existent id get one identical `400`. h2h answers only for a friend or a past opponent; a stranger and a non-existent id get one identical `403` with no name. h2h against yourself is a `400` (P4-11). | The "Athlete ID" paste box and the Profile panel's copyable Athlete ID are gone. Friends are made by email. |

### Decisions worth keeping

* **"Was this calorie figure calculated?"** is answered by re-running the calculator on the
  old type and duration at the current weight. If the weight changed since logging, a
  calculated figure looks typed and is kept on a duration change. A blank field still
  forces a recalculation. Exact provenance would need a column; that wasn't judged worth
  a migration.
* **Logout still ends every session.** That is documented and deliberate. Only login and
  refresh stopped doing it.
* **Was still open, fixed 2026-10-08 (Phase 16):** access tokens were never checked
  server-side, so after a password change or reset, one already issued worked for up to 48h.

### Verification

Suite: **490 passing** (461 before). New: `ActivityServiceEditCaloriesTest` (10),
`SessionRevocationIntegrationTest` (4), `RefreshTokenServiceTest` (4), and 8 friends-only
cases in `ChallengeServiceTest`. Cases were also added to `DeadSessionRecoveryIntegrationTest`,
`ActivityControllerIntegrationTest` and `GroupChallengeIntegrationTest`. The frontend
halves pass `node --check`. P4-2, P4-3 and P4-4 were later driven in Chrome by the Phase 13
Selenium run (all passing). P4-1's dead-session export path has not been browser-tested.

---

## Phase 13 — Frontend hardening: no inline handlers, CSP, confirm modal, wake notice, tests (2026-10-07)

Frontend only, no backend changes. The ES-module split was kept as it was.

**What changed**

* **Event delegation instead of inline handlers.** All ~95 `onclick=`/`onsubmit=`/`onchange=`/`onerror=` attributes are gone, from `index.html` and from the panels' templates. Markup now names an action (`data-action`, `data-submit`, `data-change`). `js/actions.js` routes events with one delegated listener per type, and `main.js` maps each name to its function. The 56-function `Object.assign(window, …)` list is gone; nothing is put on `window`. `renderPager()` takes a function instead of a global's name.
* **Content-Security-Policy** (meta tag, since GitHub Pages can't send headers). `script-src` has no `'unsafe-inline'` and no `'unsafe-eval'`. The one inline `<script>` (the file:// warning) moved to `js/file-protocol-check.js`. `style-src` still allows inline styles (~150 `style=""` attributes; moving them is a rewrite). The trade-offs are listed in AGENT.md §8.
* **Confirm modal.** The six `window.confirm()` calls (delete workout/goal/meal, remove friend, leave/cancel challenge) use `confirmDialog()` in `ui.js`, built on the existing modal (focus trap, Escape, focus return). Focus starts on Cancel, so Enter can't destroy anything. Cancelling a challenge reads "Keep it" / "Cancel challenge" rather than "Cancel" / "Cancel".
* **"Waking up the server" notice** (`js/wake.js`) for Render's 30–60s cold start. It is armed only when the server hasn't answered for 14 minutes (or not yet on this page), appears after 3s, and goes on the first response. A slow Gemini call on a warm server never triggers it, and neither does localhost.
* **Bug found by the new tests:** a corrupt `fp_user` in localStorage threw inside `state.js` at import, which failed every module and left a login form whose buttons did nothing. It is now treated as signed out.

**Tests**

* `frontend/tests/`: **87 unit tests** on Node's built-in runner (`node --test "tests/*.test.mjs"`, Node 22+). There's no npm and no `package.json`; `tests/helpers/env.mjs` is a small fake DOM. They cover `api()` (bearer header, refresh-and-retry, both dead-session paths, `raw`, network errors), login/register/logout, session restore, the confirm dialog, modals, toast, pager, delegation, the wake notice and utilities. `markup.test.mjs` fails on any inline handler, any unregistered or unused action name, a native dialog, a `window` global or a weakened CSP.
* **CI** now runs on `frontend/**` too, with a `frontend-tests` job alongside the backend one.
* `frontend/tests/e2e/regression_selenium.py`: a **31-check Selenium suite**, committed this time. Its docstring has the setup. It needs a live backend, so it isn't in CI.

**Verification (2026-10-07, local MySQL backend, headless Chrome 154)**

* Selenium on the untouched pre-change frontend (the baseline): **28 passed, 0 failed**, plus 3 n/a for features it doesn't have. On the new frontend: **31 passed, 0 failed**. The checks drive every panel and every action by clicking. They also cover CSP blocking an injected `onerror` and reporting it, the wake notice against a deliberately slow fake backend (shown at 3.3s, gone on the response), file://, mobile sidebar, deep link + Back, and a console with 0 errors and 0 CSP violations.
* The run also drove Phase 12's frontend halves in a real browser: an activity edit (P4-2), the session surviving a password change (P4-3), and friends-only challenges created from the picker (P4-4).
* Backend suite, unchanged by this phase: 490 passing.

---

## Phase 14 — P4 fixes, continued (2026-10-07)

`PLAN.md` P4-5 to P4-7. As before, each fix started with a test that failed on the old code.

| | Fix |
|---|---|
| **P4-5** | Rate limiting **fails closed**. `tierFor()` was an allowlist, so friends, challenges, notifications, goals, health, pain, profile, plans and stats had no limit at all. Every `/api/**` route is now at least `STANDARD` (100/min per user). A new `INVITE` tier (10/min per user) covers friend requests and new challenges, the two requests that notify someone else. |
| **P4-6** | A pain log can only link the caller's own activity (`403` otherwise), and an unknown id is a `400` instead of being silently dropped. Worse than listed: `fk_pain_log_activity` has no ON DELETE rule, so **any linked workout could never be deleted** (`409`). That included your own links, and a stranger could pin yours. `deleteActivity` now unlinks pain logs first, and the pain history survives. |
| **P4-7** | Five client mistakes that came back as `500` are now `400`s that name the problem. Passwords over 72 bytes (BCrypt's limit, measured in UTF-8 bytes) on register, change and reset, via the new `PasswordPolicy`. A missing activity `type`, pain `bodyPart` or recommendation `activityId`. Pattern detection skips old null-`bodyPart` rows. `/auth/refresh` gives a 401 only for its own refusals; other faults are a logged `500` instead of a 401 that leaked the internal message. |

### Verification

Suite: **509 passing** (490 after Phase 12). New: `PainLogIntegrationTest` (4),
`BadInputIntegrationTest` (8), `AuthControllerRefreshTest` (3), and 5 cases in
`RateLimitingFilterTest`. Two tests there asserted unlisted routes were unlimited and were
rewritten. Backend only: no frontend change was needed.

The repo was put under git the same day (`main`, pushed to GitHub), so from here on each
change has its own history.

---

## Phase 15 — P4 finished (2026-10-07 to 2026-10-08)

`PLAN.md` P4-8 to P4-20, which closes P4. Each fix started with a test that failed on the old
code, and each is its own commit.

| | Fix |
|---|---|
| **Wrong numbers** | **P4-8** monthly week-over-week compares the last 7 days with the 7 before (a steady month read +300%, a drop read positive); `null` when there is no earlier week. **P4-9** CSV export no longer turns negative numbers into text. **P4-10** the digest's net calories use the burn on the logged days only. **P4-12** an empty body-metrics save is a `400`, and an unknown value is stored as `null`, not `0`. |
| **Dead sessions** | **P4-18** 21 reads (the plan listed about 10) answered a deleted account with an empty page instead of the `409`. `AccountGuard` fixes them, and a test that discovers every `GET` route from Spring covers any future one. |
| **Bad input** | **P4-17** pain severity, water, sleep, difficulty rating and weight/height/age are range-checked from one `InputChecks` class. Sleep quality was silently clamped, so a missing one was saved as 1. |
| **Challenges** | **P4-13** `accept` is required (a missing one declined the invite). **P4-14** a challenge cancelled with no contest carries its message and is announced as cancelled, not as a draw. **P4-15** someone who declined or left no longer blocks a new challenge. |
| **Meals and lists** | **P4-16** editing a meal derives calories from its macros, as logging does. **P4-19** recommendations are paged and newest first (a response-shape change, with the frontend updated), challenges are newest first, plan days are in day order. |
| **Leftovers** | **P4-20** the unused `PERSONAL_BEST` notification type is removed; `JacksonConfig` is verified and its wrong comment corrected; `enroll` needs no body. |

### Found along the way

* `GET /api/plans/{planId}` read a lazy collection after its transaction ended, which only
  worked because production runs `open-in-view=true` (the test profile doesn't). It loads the
  days in the transaction now.
* The test profile and production differ on that setting. It is a known trap, not changed here.

### Verification

Backend suite: **554 passing** (509 after Phase 14). Frontend unit tests: 87. New test
classes include `DeadSessionReadsIntegrationTest`, `InputValidationIntegrationTest`,
`ListOrderingIntegrationTest`, `BodyMetricsServiceInputTest`, `InputChecksTest`,
`StatsServiceMonthlyTest` and `LeftoversIntegrationTest`. The frontend halves of P4-14 and
P4-19 pass their syntax and unit checks but have **not been driven in a browser**.

---

## Phase 16 — Access tokens can be retired (2026-10-08)

The one real security gap left after P4 (it was recorded as "still open" under P4-3).
Revoking refresh tokens stops a device renewing its session, but an access token it already
held was a signed JWT that nothing checked against the account, so it worked for up to 48 hours
after a password change or reset.

* **A per-account `tokenVersion`** (V9 migration, `fitness_user.token_version`, default 0). Each
  access token carries it as the `tv` claim, `JwtAuthenticationFilter` compares the two on every
  request, and a mismatch is a `401` (the code the frontend answers by refreshing; the refresh
  token is gone too, so it ends at the login screen).
* **A password change or reset adds one.** The change hands the caller a fresh pair at the new
  version, so that device stays signed in. A reset hands nothing back: the person logs in.
* **Nobody is signed out by the upgrade.** A token with no claim counts as 0, and every account
  starts at 0.
* **A deleted account is not refused in the filter**, so it still reaches the 409 that the
  frontend recovers from (Phase 14's dead-session work). A 401 there would loop through refresh.
* **Cost:** one small query per authenticated request (just the integer, not the user row).
* **Not changed here:** logout still revoked refresh tokens only, so a logged-out access token
  lived out its 48 hours. Fixed in Phase 17.

### Verification

Backend suite, re-run clean on 2026-10-11: **563 passing**, 0 failing (554 before). New: 6 filter
tests (match, stale, newer, no claim, deleted account, and the chain running once), 2 `JwtUtils`
tests, an end-to-end case in `SessionRevocationIntegrationTest` (old tokens die, the fresh one and
one minted by a later refresh work, a second change retires the first), and the reset test now
checks that a token issued before the reset dies with it. A mutation check, with the version bump
disabled, makes both the change test and the reset test fail with `expected: 401` (the old token
still got a 200).

Found in review before commit: the filter's refusal originally continued the chain from inside
its `try` block, so an exception from further down was caught and the chain ran a second time.
The refusal now just leaves the request unauthenticated, and the chain runs once, after the
`try`. The sixth filter test fails on the old structure.

---

## Phase 17 — Logout ends access tokens too (2026-10-11)

Logout already revoked every refresh token the account had, on every device, and answered "All
sessions revoked". But each device's access token kept working for up to 48 hours, so that was
only true of renewal. Phase 16's token version is the mechanism; logout just didn't use it.

* **`RefreshTokenService.endAllSessions()`** revokes the refresh tokens and adds one to the token
  version, in one transaction. Logout calls it instead of `revokeAll()`.
* **The bump is an atomic `UPDATE`** (`UserRepository.retireIssuedTokens`), not a load-and-save:
  logout has only an id, and two racing calls both count.
* **The behaviour of the endpoint is otherwise unchanged:** same proofs of identity (access
  token, or the refresh token in the body), same `200` in every case, same messages. The frontend
  needed no change: it already clears its storage on logout, and another device's next call is a
  `401`, then a failed refresh, then the login screen.
* **One consequence, closed the same day (below):** logout could be proved with a refresh token
  that was already revoked or expired, until the daily purge deleted it. Whoever held one could
  already revoke the account's refresh tokens with it; now that ended the access tokens too.

### Verification

Backend suite: **566 passing**, 0 failing (563 before). New: two end-to-end cases in
`SessionRevocationIntegrationTest` (logout with an access token kills both devices' access tokens
and the other device's refresh, and logging back in works; logout with only a refresh token kills
the access token too) and one in `RefreshTokenServiceTest`. A mutation check, with the bump
removed from `endAllSessions`, makes both end-to-end cases fail with `expected: 401`.

### Logout needs a live refresh token

`RefreshTokenService.resolveUserId` now identifies the owner only of a refresh token that is
neither revoked nor expired. A spent one (rotated away, revoked by an earlier logout, or past its
30 days) gets the existing `200` "Logged out locally. No active session to revoke." and ends
nothing. The access-token proof is unchanged, so a device whose refresh token was rotated by
someone else can still log everyone out while its access token lasts; a password change is the
remedy beyond that.

Backend suite: **570 passing**, 0 failing (566 before). New: three `RefreshTokenServiceTest` cases
(live, revoked, expired) and one end-to-end case in `SessionRevocationIntegrationTest` (a rotated
token's logout ends nothing; the live session still works and still refreshes). With the filter
removed, all three that cover a spent token fail.

---

## Phase 18 — End-to-end QA audit (2026-10-11)

The first pass that tested the running product rather than the code. The backend ran on a fresh
MySQL database (`fitpilot_qa`, built by Flyway from V1 to V9) with the real Gemini key and email
switched off, and was driven three ways:

* **Over HTTP:** a 104-check script covering every endpoint group with positive, negative,
  boundary and cross-user (403) cases. It also checked token tampering and `alg=none`, CORS,
  actuator exposure, all four rate-limit tiers at their production values, and password reset
  through the logged link.
* **In real Chrome** through the DevTools protocol, with no Playwright or npm:
  * every panel, at desktop and phone width;
  * registration, login, logout and reload through the forms;
  * the write flows: workouts with exercises, rating, edit, delete via the confirm modal,
    meals, goals, pain, water, plans, profile, password change and export;
  * friends and challenges, and the AI features against Gemini;
  * silent refresh, a session revoked elsewhere, the reset link, and HTML in a name (shown as
    text, never run);
  * console errors and failed requests captured throughout.
* **The repo's Selenium suite**, `tests/e2e/regression_selenium.py`, run as documented.

It found the 17 items listed as **P5** in `PLAN.md`, plus 6 observations (O-1 to O-6) that
are recorded there as decisions rather than bugs. Ten items were fixed. Two are High security
issues:

* **The login rate limit could be bypassed** (P5-1). It trusted a client-written
  `X-Forwarded-For`.
* **A crafted `?api=` link hijacked the deployed frontend** (P5-2). It sent the visitor's
  password and tokens to the link's host.

The other eight fixes:

* Workouts had no numeric limits (P5-3), and one fake entry could win any challenge.
* Activity goals were counted three different ways (P5-4), so deleting a workout raised a goal.
* Over-long text came back as a misleading 409 (P5-5), and the UI had no `maxlength`.
* Nonsense descriptions were saved as workouts (P5-6).
* Challenges had no length limit (P5-7).
* Meals and sleep accepted impossible values (P5-8).
* Login timing revealed which emails have accounts (P5-9).
* A reset token lingered in the address bar (P5-10).

Seven items stay open:

* **No sleep form in the UI (P5-11).** This is the one feature a user cannot reach.
* **Lower risks:**
  * Forgot-password timing (P5-12).
  * Refresh tokens stored unhashed (P5-13).
  * Production allows localhost and `null` CORS origins, exposes Swagger and logs SQL (P5-14).
  * Natural-language descriptions have no length limit before they go to Gemini (P5-17).
* **Deployment configuration:**
  * Without a mail key, live reset links go to the log (P5-15).
  * Without `FRONTEND_URL`, reset links point at localhost (P5-16).

The observations:

* The Activities list is ordered by entry time, not workout time (O-1).
* The streak pill wraps at phone width (O-2).
* An error-styled toast appears for an empty digest week (O-3).
* AI-logged workouts with no duration get no calories (O-4).
* One reload made two refresh calls (O-5, not investigated).
* Tokens live 48 hours in `localStorage` (O-6).

Decisions worth knowing:

* **The `?api=` override is not removed outright.** On a deployed origin it is ignored unless it
  names a loopback address, and even then it lasts one page load and is never saved. The repo's
  Selenium wake-notice check points a deployed-looking page at a slow local fake server this way,
  and a remote attacker cannot receive what goes to the visitor's own machine.
* **The rate-limit fix depends on the proxy setup.** It relies on Tomcat's RemoteIpValve
  trusting Render's proxy, which connects from a private address, as in Tomcat's default list.
  That holds locally (simulated) but has to be confirmed on the first deploy.
* **Goal creation now fills activity goals.** `API.md` used to document "an activity goal starts
  at 0.0" as intended, but the rebuild never agreed with it, so the documented behaviour was
  itself the bug.

### Verification

* **Before:** backend 570, frontend 87.
* **After:** backend **587 passing**, frontend **95 passing**, and the Selenium suite **31 of 31**.
* **New tests:**
  * `GoalProgressIntegrationTest` (3) and `NaturalLanguageActivityServiceTest` (5).
  * Six cases in `InputValidationIntegrationTest`.
  * One each in `RateLimitingFilterTest`, `ChallengeServiceTest` and `UserServiceTest`.
  * Frontend: `config.test.mjs` (7) and `reset-link.test.mjs` (1).
* **Each fix's tests fail on the code before it.** With the main sources reverted, 14 of the new
  backend tests failed, and 3 of the 5 original config tests. The Chrome hijack test showed the
  password reaching a remote HTTPS attacker on the old code and nothing on the new.
* **Live re-test after the fixes:** all 104 HTTP checks pass, and the browser scenarios were
  re-run.
* **Not covered:**
  * the dead-session paths in a browser, since they need a row deleted under a live session;
  * real email delivery, since email was off;
  * the deployed environment itself.

---

## Phase 19 — A Log Sleep form (2026-10-16)

Fixes P5-11, the one feature the QA audit found a user could not reach. Sleep could only be
written through the API, so the Health panel's sleep card and the sleep-vs-next-day chart were
always empty in the app.

* **Where:** a "+ Log Sleep" button on the "Sleep — Last 7 Nights" card opens a modal, built
  like Log Pain. Saving reloads the card and the chart.
* **Fields:**
  * **Night of:** defaults to yesterday, as the server does, and stops at today.
  * **Bedtime and wake time:** optional clock times.
  * **Hours slept:** required, and filled in when both times are given.
  * **Quality:** a required 1–5 choice with no preselected value. A silent default is what
    the API stopped allowing in P4-17.
  * **Notes:** at most 255 characters, matching the column.
* **Times:** the API takes `LocalDateTime`s. `sleepTimes()` places the clock times on the right
  days: the night is the evening of `sleepDate`, so a bedtime from noon on is that evening and
  an earlier one is past midnight. Waking is the first time the clock shows the wake time
  after bed.
* **Logging the same night again replaces it**, as `POST /api/health/sleep/{userId}` always
  has. The form says so.
* **No API change.** The endpoint and its rules were already in place.

### Verification

* **Frontend suite:** **104 passing** (95 before). The new `tests/sleep.test.mjs` (9) covers:
  * placing times before and after midnight, and across a year end;
  * either time left out;
  * the hours fill-in;
  * the defaults when the form opens;
  * the exact body posted;
  * nulls for omitted fields.
* **In Chrome against MySQL:** 19 of 19 checks passed. The scenario:
  * open the form from a new account's empty card;
  * check the defaults;
  * fill in the hours from 23:10 and 06:40;
  * confirm that a missing quality is blocked in the browser;
  * save, and check the stored row (`2026-10-15T23:10` to `2026-10-16T06:40`), the card, and the chart reload;
  * log a second night with no times (stored as nulls);
  * check that the server's refusal of a future night shows as a toast and leaves the form open;
  * check 390 px width with no horizontal scroll and no console errors.
* **Backend:** unchanged, at 587.

---

## Phase 20 — Form spacing, and the last three browser checks (2026-10-16)

### Form rows had no gap below them

`.field-row .field{margin-bottom:0}` takes the spacing off the fields inside a two-column row,
and nothing gave it back to the row. So every row sat flush against whatever came next. That
affected Sign Up, Edit Profile, Log and Edit Workout, Log and Edit Meal, and the new Log Sleep
form, which had patched it inline.

* **The fix:** `.field-row` now carries the same 14px bottom margin as a `.field`.
* **Hints stay attached:** `.field-row + .field-hint` pulls a hint under a row back to its
  original 6px.
* **Measured in Chrome:** every row-to-next gap in all seven forms is 14px, up from 0. Both
  hints that follow a row are still 6px.

### The three frontend halves never driven in a browser

All three were fixed and covered over HTTP in P4, but their browser side had not been seen
working. Each check was driven in Chrome against the local backend on the throwaway
`fitpilot_qa` database. No bugs were found.

* **P4-19, the AI Coach pager:**
  * **Setup:** 10 insights from real Gemini calls.
  * Page 1 shows 9 insights, with "1 of 2" and Prev disabled.
  * Next shows the 10th, with Next disabled, and Prev returns to page 1.
  * The overview teaser asks for `size=1` only.
* **P4-14, a cancelled challenge's card:**
  * **Setup:** a real challenge with a friend who never accepted.
  * **Simulated step:** the nightly settlement runs at 00:05, so it was not run. Instead,
    the three columns `declareWinner()` writes for "nobody else joined" were set directly.
  * The API returns the message.
  * The card shows CANCELLED and "Nobody else joined, so there was no contest."
* **P4-1, export from a dead session:**
  * **Setup:** for each of CSV, zip and JSON, a signed-in account whose `fitness_user` row
    was deleted under the live page.
  * The export request got the `409`. The page returned to login with the server's message,
    and the saved session was cleared.
  * No file was downloaded, and there were no console errors.
  * **Test-script note:** deleting the row before the page's first calls had finished made
    those calls hit the 409 instead. That is the same self-heal, reached from a different
    request.
