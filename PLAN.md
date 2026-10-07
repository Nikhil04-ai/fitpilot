# PLAN.md — What's Left

`PROGRESS.md` records what exists. This file records what's **outstanding**, in
priority order, so priorities aren't re-derived from scratch every session.

> **How this file was written.** Every item below was verified by reading the code on
> 2026-09-21, not by reading the docs. Where a feature is listed as "Done" in
> `PROGRESS.md` but is only partly wired, that's called out with the file and line
> that proves it.
>
> **Status, 2026-09-21:** all seven **P0** items are now fixed, backend and frontend,
> and the frontend half was driven in real Chrome (15/15 UI checks). Their entries are
> kept below as a record of what was wrong and how it was closed.
>
> **Status, 2026-09-22: all five P1 items are done.** Flyway, charts, exercise-level
> logging, tiered rate limiting and password reset all landed, in that order, and the
> two loose ends P0 left behind are closed with them. The suite is at **287 passing
> tests** (was 190), and each frontend half was driven in real Chrome — 21/21 checks
> for the charts, 21/21 for exercise logging, 13/13 for password reset.
>
> **Status, 2026-09-25: all five P2 items are done.** Nutrition, data export, the
> weekly AI digest, the notification delivery layer and friends + group challenges, in
> that order. See `PROGRESS.md` Phase 9 for what shipped and the decisions behind it.
>
> **Both follow-ups are done (2026-09-25):** the full suite re-ran green, and Phase 9 was
> verified live on real MySQL and driven in Chrome. That surfaced five bugs —
> the worst being a retired Gemini model that had every AI feature silently on its
> fallback — all fixed; suite now **443 passing**. See `PROGRESS.md` Phase 9.
>
> **Status, 2026-09-25: P3 is done** (`API.md` for all 83 operations, and paged history).
> Writing `API.md` turned up the 20 problems listed under **P4**.
>
> **Status, 2026-10-08: P4 is 19 of 20 done.** All seven bugs and security problems (P4-1
> to P4-7), P4-11 (closed with P4-4), all four wrong numbers (P4-8, P4-9, P4-10, P4-12) and
> P4-13 to P4-19 are fixed. Only P4-20, a set of leftovers, is open. Suite: **551 passing**. See `PROGRESS.md` Phases 12 and 14. The project has been in git
> since 2026-10-07 (`main`, on GitHub).

## Priority key

| | Meaning |
|---|---|
| **P0** | Shipped feature that is broken, dead, or silently lying to the user. Fix before building anything new. |
| **P1** | Foundation work that unblocks several P2 items, or a real correctness/ops risk. |
| **P2** | New capability with clear user value. |
| **P3** | Polish. Do when the above is clear. |

Effort: **S** ~ half a day · **M** ~ 1-3 days · **L** ~ a week+

---

# P0 — Shipped but broken · ALL FIXED 2026-09-21

Each of these was a feature the app *claimed* to have, where the code proved otherwise.
Kept here as a record; each entry now ends with what was actually changed.

| | Was | Now |
|---|---|---|
| P0-1 | `ProgressionService` had zero callers; no rating UI existed | Wired into `rateActivity`; clickable 1–5 control on every row |
| P0-2 | Parsed distance/sets/reps stored and never shown | Rendered as chips on the activity row |
| P0-3 | PR badge read a field the list endpoint never returned | List returns `ActivityResponse`; badge marks the record holder |
| P0-4 | No way to edit or delete an activity | `PUT` + `DELETE`, with full recalculation of derived state |
| P0-5 | `completedAt` was null on every activity ever logged | Populated as start + duration |
| P0-6 | Every PB announced as "longest session" | Message names the record that actually broke |
| P0-7 | Goal deadlines stored but never compared to today | Expired goals stop accruing and are shown as expired |

## P0-1 · Feature 13 "progression analysis" never runs — `ProgressionService` is dead code

**Evidence:** `service/ProgressionService.java` has **zero callers**. Grepping
`progressionService.` across `backend/src/main/java/` returns nothing. The post-save
fan-out in `service/ActivityService.java:66-72` calls streak, personal-best, muscle
recovery, goal and recommendation services — and never progression.

**Also missing:** there is no rating UI at all. `difficultyRating` appears **nowhere**
in `frontend/app.js`. So `POST /api/activities/{id}/rate` exists and is tested, but
nothing in the product can call it, and even if it could, the analysis it's supposed
to feed is unreachable.

`PROGRESS.md` lists Feature 13 as Done. What's actually built is the column, the DTO
field and the endpoint — roughly a third of it.

**FIXED.** `rateActivity` now calls `analyseProgression` and returns the result as
`ActivityResponse.progressionSuggestion`; `ratingStars()` in `app.js` renders a
clickable 1–5 control on every activity row and surfaces the suggestion as a toast.

## P0-2 · `additionalMetrics` is write-only — distance, sets and reps are discarded

The single highest-value fix in this file, because it makes three features honest at once.

**The whole path exists and then stops:**

| Step | Where |
|---|---|
| Gemini is asked for `distance_km`, `sets`, `reps` | `service/GeminiService.java:81-86` |
| Parsed values passed through | `service/NaturalLanguageActivityService.java:52-55` |
| Saved to the JSON column | `service/ActivityService.java:60` |
| Returned by the API | `service/ActivityService.java:143` |
| **Rendered anywhere** | **nothing** |

`activityRow()` (`frontend/app.js:484-498`) renders type, notes, duration, calories
and a PR badge. No metrics. So a user types *"ran 5km in 30 minutes"*, the model
correctly extracts 5km, it is stored — and it is never seen again by anyone. No
service reads it either: grepping `getAdditionalMetrics` returns only the DTO mapping.

**FIXED (display).** `metricChips()` renders them on every row, with friendly labels
for the keys Gemini returns and a generic fallback for anything else.

**Still open:** no *service* reads them yet — distance still doesn't reach stats or
personal bests. That part belongs to P1-1, where these become real columns.

## P0-3 · The "PR" badge on the activity list can never render

**Evidence:** `frontend/app.js:496` reads `a.isPersonalBest`, but the list is populated
from `GET /api/activities`, which returns `Page<Activity>` — the **raw JPA entity**
(`controller/ActivityController.java:61`). `Activity` has no `isPersonalBest` field;
it only exists on `ActivityResponse` (`dto/ActivityResponse.java:34`), which is
returned by the POST path. So the property is always `undefined` and the badge is
permanently dead in the list.

It *does* work on the toast right after logging (`frontend/app.js:584`), which is
probably why this went unnoticed.

**Secondary problem:** returning the entity directly from GET also means the list
silently carries a different shape than the create response — `difficultyRating` is
there but `currentStreak`, recovery warnings and goal achievements are not.

**FIXED.** `getUserActivitiesPage` returns `Page<ActivityResponse>`, and marks the row
that currently holds its type's record. Covered by `list_returnsDtoShape`.

## P0-4 · No way to edit or delete a logged activity

**Evidence:** `controller/ActivityController.java` exposes exactly four routes —
`POST /`, `GET /`, `POST /{id}/rate`, `PUT /{id}/notes`. No `PUT /{id}`, no
`DELETE /{id}`. Nothing in `frontend/app.js` issues a DELETE.

A typo'd duration is permanent, and because the post-save fan-out writes derived
state (streak, personal bests, muscle recovery, goal progress, challenge scores), one
bad entry permanently corrupts all of it. There is no repair path short of SQL.

**FIXED.** `PUT /api/activities/{id}` (partial — omitted fields are kept) and
`DELETE /api/activities/{id}`, both behind the same ownership check, plus an edit modal
and a delete button on each row.

The recompute is the substance: **`ActivityRecalculationService`** rebuilds streaks,
personal bests, muscle recovery and goal progress from the activity rows, because none
of those can be undone incrementally. Deleting a record-setting workout now lowers the
record; deleting a mid-streak day now breaks the streak. 9 unit tests + 2 integration
tests, including a cross-user 403 on delete.

## P0-5 · `Activity.completedAt` is a dead column

**Evidence:** `model/Activity.java:39` declares it; the builder in
`service/ActivityService.java:54-62` never sets it; the only `setCompletedAt` in the
codebase is on a plan enrollment (`service/WorkoutPlanService.java:101`). It is mapped
into every response as a permanent `null`.

**FIXED.** Populated as `startTime + duration` on both create and edit. Covered by
`create_populatesCompletedAt`.

## P0-6 · Personal bests are meaningless for strength training

**Evidence:** `PersonalBest` stores `bestDuration` and `bestCalories` per
`ActivityType` (`model/PersonalBest.java`), and `PersonalBestService.checkAndUpdate`
compares only those two. For `WEIGHT_TRAINING`, "longest session" and "most calories"
are not achievements — the number anyone cares about is weight moved.

**Related bug:** `service/ActivityService.java:150` hardcodes the message to
`"New personal best — longest ... session!"` even when the record that broke was
calories.

**FIXED (message).** `PersonalBestService.evaluate` returns a `Result` naming which
record broke — duration, calories, or both. `checkAndUpdate` keeps its boolean
signature so its existing tests stand.

**Still open:** the underlying model. Duration and calories remain the only records
tracked, which is still meaningless for `WEIGHT_TRAINING`. That is P1-1.

## P0-7 · Goal deadlines are decorative

**Evidence:** `Goal.deadline` is stored (`GoalService.java:42`), updatable
(`GoalService.java:125`) and returned (`GoalService.java:150`) — but `GoalService`
never compares it against `LocalDate.now()`. A goal whose deadline passed a month ago
still reports as active and in progress, forever.

**FIXED.** `GoalService.isExpired` gates accrual and roll-over, so an expired goal
stops accumulating and is no longer reset to a fresh-looking 0 every Monday. The API
returns `isExpired` and `daysRemaining`; the UI dims expired goals, badges them, shows
a warning when a deadline is within 7 days, and drops them from the overview.

---

# P1 — Foundation · ALL DONE 2026-09-22

Done in the order the sequencing below called for: Flyway first so the schema change
in P1-1 could not diverge, charts next because they had no schema dependency, then the
`Exercise` entity, then the two smaller items. Each entry now ends with what shipped.

| | Was | Now |
|---|---|---|
| P1-1 | `Activity` was duration + calories; no way to record a lift | `Exercise` child entity, per-movement records with estimated 1RM, distance promoted to a real column |
| P1-2 | `ddl-auto=update`, silently diverging dev from Aiven | Flyway owns the schema; `ddl-auto=validate`; drift fails the build |
| P1-3 | 12 panels of numbers and text, zero charts | Four charts: volume, peak-hour, weight/BMI, sleep-vs-performance |
| P1-4 | A forgotten password was a dead account | Emailed single-use reset link, all sessions revoked on use |
| P1-5 | Rate limiting covered one path; login was uncapped | Three tiers: auth (IP-keyed), AI, standard |

## P1-1 · Exercise-level logging (`Exercise` entity: sets, reps, weight)

The structural gap behind P0-2 and P0-6. `Activity` models a workout as *duration +
calories*, which fits running and fits nothing about lifting. `ActivityType` has a
`WEIGHT_TRAINING` member and no way to record a single lift.

**Done means:** `Exercise` child entity under `Activity` (name, sets, reps, weight);
PBs computed per exercise including estimated 1RM; `additionalMetrics` promoted to
real columns where it's structured; Gemini's parse prompt extended; progression
(P0-1) driven by load rather than by self-reported difficulty.

This is what turns FitPilot from a cardio log into a training app.

**Effort:** L

**DONE 2026-09-22.** `Exercise` (name, sets, reps, weightKg, orderIndex) hangs off
`Activity`, with volume and an Epley 1RM estimate computed server-side so the number on
screen is the one the record was judged against. `ExercisePersonalBest` holds heaviest
set, best estimated 1RM and best session volume per movement, matched on a normalised
name so "Bench Press" and "bench  press" are one record. `ActivityRecalculationService`
rebuilds them after an edit or delete, for the same reason it rebuilds everything else.

Both loose ends P0 left are closed with it:

* **`additionalMetrics` is now read, not just stored.** `distanceKm` is a real column.
  It still accepts the old `distance_km` blob shape from the parser and older clients
  and promotes it, and `V3` backfills the rows that already had one.
* **Personal bests mean something for lifting.** Per-movement records above, plus
  `bestDistanceKm` on the activity-level record so a runner has something to beat too.

Two things worth knowing that weren't in the original plan:

* **Bodyweight movements record reps.** Pull-ups have no load, so weight, 1RM and
  volume are all null and the record card was three dashes. `bestBodyweightReps` is
  tracked only for unloaded sets — 20 reps with an empty bar is not 20 strict pull-ups.
* **Progression is load-driven where load exists.** `ProgressionService` compares each
  movement's estimated 1RM across its last three sessions and names the ones that have
  stalled. The old difficulty-rating heuristic is kept as the fallback for cardio and
  for anyone not logging sets — a 1–5 rating is a real signal, just a noisy one.

## P1-2 · Flyway, replacing `ddl-auto=update`

**Evidence:** `backend/src/main/resources/application.properties:18` is
`spring.jpa.hibernate.ddl-auto=update`. Already named as backlog in `CLAUDE.md`.

`update` never drops or narrows a column, so the first real migration — and P0-5,
P0-6 and P1-1 are all schema changes — will silently diverge between dev and the
Aiven instance. Do this **before** P1-1, not after.

**Effort:** M

**DONE 2026-09-22.** `ddl-auto=validate`, and `db/migration` owns the schema.

* **V1 is the baseline**, generated from the entity mappings by Hibernate itself
  (MySQLDialect) rather than transcribed by hand, so it matches what is already live
  column for column. `baseline-on-migrate=true` stamps existing databases at V1 instead
  of trying to re-create their tables.
* **The migrations are actually exercised.** `FlywayMigrationTest` runs them on an
  isolated H2 database in MySQL mode with `ddl-auto=validate`, so the context refresh
  *is* the assertion: add a field without the matching `V<n>__` file and it fails to
  start, naming the column. That is precisely the drift `update` used to hide until it
  reached Aiven. The rest of the suite stays on `create-drop` — it shares one in-memory
  database across cached contexts, and create-drop is what resets it between them.
* **One gotcha worth recording:** Spring Boot 4 moved auto-configuration out of
  `spring-boot-autoconfigure` into per-module jars. With only `flyway-core` on the
  classpath, Flyway sits there and never runs, with no warning at all. The build needs
  `spring-boot-flyway` as well (and `flyway-mysql`, since MySQL support left core in
  Flyway 9).
* **Vendor-specific SQL lives in `db/vendor/{vendor}`, not under `db/migration`.**
  Flyway scans a location recursively, so a nested vendor folder would be fed to the
  H2 test too. They share one version namespace, which is why V3 (a MySQL-only JSON
  backfill) is followed by V4 and H2 legitimately sees a gap.

## P1-3 · Charts

**Evidence:** zero `<canvas>` elements across `frontend/index.html` (753 lines) and
`frontend/app.js` (1456 lines). Every one of the 12 panels is numbers and text.

The data is already computed and already unseen: `SleepService` derives a
sleep-vs-performance correlation, `BodyMetricsService` keeps BMI history,
`StatsService` aggregates weekly and monthly, `TimingAnalysisService` finds a peak
hour. All of it is rendered as sentences.

Chart.js from a CDN keeps the no-build-step rule intact (`CLAUDE.md`, Tech Stack).

**Done means:** weight/BMI trend, weekly volume, sleep-vs-performance scatter, a
peak-hour histogram.

**Effort:** S-M · highest perceived-quality gain per hour in this document

**DONE 2026-09-22.** All four, Chart.js from a CDN tag, no build step. Two of them cost
no extra request — the weight/BMI trend draws the history `/api/health/metrics` was
already returning, and the peak-hour histogram draws a `hourlyDistribution` that
`TimingAnalysisService` was already computing internally and throwing away. Two needed
new endpoints: `GET /api/stats/daily` and `GET /api/health/sleep/{id}/performance`.

Three decisions that are about honesty rather than looks, and are easy to undo by
accident:

* **Rest days are explicit zeros**, and the volume line has no spline smoothing. A
  series containing only the days you trained draws a rest week as a straight line
  between two workouts, and makes a gap look like consistency.
* **The weight axis does not start at zero.** It would compress every real change into
  a flat line at the top of the chart.
* **Rest days are plotted in the sleep scatter but excluded from the correlation.** A
  week of good sleep and no training is not evidence that sleep hurts performance.

## P1-4 · Password reset

**Evidence:** `AuthController` has register, login, refresh, logout. Password change
is `PUT /api/profile/{userId}/password` — which requires being logged in already.
There is no recovery path: a forgotten password is a dead account.

Needs an email transport, which nothing in the stack currently has.

**Effort:** M

**DONE 2026-09-22.** `POST /api/auth/forgot-password` and `POST /api/auth/reset-password`,
with a "Forgot your password?" link on the login screen and a set-password view that
opens from `?reset=<token>`.

**On the transport:** HTTPS to an email API (Resend-shaped, `app.mail.api-url` points
it elsewhere) rather than `spring-boot-starter-mail`. Render blocks outbound SMTP on
the free tier, so an SMTP client would compile, pass every test, and then silently
deliver nothing on the deployed instance. It reuses the `RestClient` already configured
for Gemini, and degrades the same way: with no key the reset link goes to the log, so
the flow is completable locally and a missing key never becomes a 500 for someone
trying to recover their account.

The properties that matter, all deliberate:

* Requesting a reset answers **identically for a known and an unknown address** —
  otherwise the endpoint is a free "does this person have an account here" lookup.
* **Only a SHA-256 hash of the token is stored.** A live reset token is a password
  equivalent, and a leaked backup must not hand over working ones. SHA-256 and not
  BCrypt: the token is 256 random bits, so there is nothing to make guessing expensive.
* **Single use, 30 minutes, one outstanding grant** — requesting again kills the link
  already sent, so an old email left in an inbox stops working.
* **A reset revokes every refresh token.** People reset precisely when they think
  someone else has their password.
* Bad, spent and expired tokens all produce **the same message**, so the response
  cannot be used to learn which guessed tokens were once real.
* The frontend **strips the token from the address bar** once it has read it, so it
  does not sit in history or leak in a Referer header.

**Fixed on the way past, because this feature depended on it:** nothing mapped the
`FRONTEND_URL` env var that `render.yaml` sets to the `app.frontend-url` property that
`SecurityConfig` reads — Spring maps that variable to `frontend.url`. So the deployed
frontend origin was silently never added to the CORS allow-list, and reset links would
have pointed at localhost in production. One line in `application.properties` binds
them.

## P1-5 · Rate limiting covers one endpoint

**Evidence:** `config/RateLimitingFilter.java:38` — `RATE_LIMITED_PATH = "/api/activities"`,
and line 71 returns early for everything else. `/api/auth/login` and
`/api/auth/register` are unlimited, so credential stuffing and mass signup are both
uncapped, and `/api/recommendation/generate` is unlimited despite each call costing a
Gemini request.

**Done means:** a stricter IP-keyed bucket on the auth routes; a cheap bucket on
AI-backed routes.

**Effort:** S

**DONE 2026-09-22.** Three tiers, all tunable via `app.rate-limit.*` so a live instance
under attack can be tightened without shipping code:

| Tier | Per minute | Keyed by | Covers |
|---|---|---|---|
| `AUTH` | 10 | **IP, always** | `/api/auth/**` |
| `AI` | 10 | user, else IP | `/api/activities/natural`, `/api/recommendation/generate` |
| `STANDARD` | 100 | user, else IP | `/api/activities` |

Two details that are load-bearing:

* **The auth tier ignores the bearer token even when one is present.** Keying on it
  would let anyone holding a single valid account reset the bucket at will — exactly
  the caller a login limit exists to stop.
* **`/api/activities/natural` is matched before the activities prefix.** It is a Gemini
  call that happens to live under that path, and it must not inherit the 100/min
  allowance.

The limits are per instance, since the buckets are in memory. Fine for one Render
service; a shared store is needed before running more than one.

---

# P2 — New features · ALL DONE 2026-09-25

| | Was | Now |
|---|---|---|
| P2-1 | Burn tracked, intake not — so no goal could be about body weight | `MealLog`, `/api/nutrition`, and three new `GoalType` members backed by `GoalPeriod` + `GoalDirection` |
| P2-2 | Three scheduled jobs wrote insight nobody was told about; one wrote nothing at all | `Notification` feed, unread badge, and four producers — including the plateau scan, which was a stub |
| P2-3 | Gemini saw age, weight, five activities and a streak | A weekly digest over training, load, nutrition, sleep, body weight, pain and goals, with the facts stored beside the prose |
| P2-4 | `Challenge` was two columns wide; no concept of a friend | `Friendship`, and `ChallengeParticipant` for up to 10 people |
| P2-5 | A year of training history with no way to get it out | `/api/export` — JSON, per-dataset CSV, or a zip of all ten |

The original entries follow, as the record of what was wrong.

| | Feature | Rationale | Effort |
|---|---|---|---|
| **P2-1** | **Nutrition / calorie intake** | The app tracks burn and not intake, so no weight goal can ever be computed. `GoalType` (`model/GoalType.java`) is only `CALORIES_PER_WEEK`, `WORKOUTS_PER_WEEK`, `DURATION_PER_WEEK` — all activity-side, all weekly. Nothing about body weight, the thing most users are actually here for. | M |
| **P2-2** | **Delivery layer for generated insight** | Three scheduled jobs write insight nobody is told about: plateau scan Mon 09:00 (`PlateauDetectorService.java:85`), challenge scoring 23:55 and settlement 00:05 (`ChallengeService.java:143,160`). Each is visible only if the user happens to open the right panel. Start with an in-app notification feed + unread badge; email digest after P1-4 brings a mail transport. | M |
| **P2-3** | **Broader AI use** | Gemini is called from exactly two places — `NaturalLanguageActivityService.java:26` and `RecommendationService.java:85`. Pain logs, sleep, recovery, timing and body metrics are all rule-based. A weekly digest reasoning over the *combined* record is where the "AI-powered" claim is actually earned. | M |
| **P2-4** | **Group challenges / friends** | `Challenge` is strictly 1-v-1 (`/h2h/{userAId}/{userBId}`), though `ChallengeLeaderboardEntry` already exists and would generalise. | M |
| **P2-5** | **Data export (CSV / JSON)** | Cheap, and the absence is conspicuous for an app holding a year of someone's training history. | S |

---

# P3 — Polish · ALL DONE 2026-09-25

P3-1 and P3-4 turned out to be done already. P3-2 and P3-3 closed on 2026-09-25, along with
the Friends email-input styling left over from Phase 9 (`PROGRESS.md` Phase 10).

| | Item | Note |
|---|---|---|
| ~~**P3-1**~~ | ~~Panel deep-linking~~ | **Already done** — this entry was stale when written. `app.js` has `panelFromHash()`, `pushState` in `nav()`, and both `hashchange` and `popstate` listeners; every panel is linkable and survives a refresh. Notification deep-links rely on it. |
| ~~**P3-2**~~ | ~~`API.md`~~ | **Done 2026-09-25.** All 83 operations, with each shape traced from controller to serialized object and checked against the live OpenAPI spec (83/83). 28 of its claims were also confirmed against a running server. |
| ~~**P3-3**~~ | ~~Pagination beyond activities~~ | **Done 2026-09-25.** Pain, sleep and body-metrics history are paged. `/metrics` now embeds only the latest 100 weigh-ins, plus `historyTotal`, and the full record is at `/metrics/{userId}/history`. Water was already bounded to 7 days. A negative `page` is now 0 on every paged endpoint; three of them used to return 500. |
| ~~**P3-4**~~ | ~~Activity row shows no date~~ | **Already done.** Fixed in passing; rows carry a date. |

---

# P4 — Found while writing `API.md` (2026-09-25)

Tracing all 83 endpoints from controller to wire turned these up. `API.md` documents each
one as it **currently behaves**, so fixing one means updating its entry there too. A
struck-through row is fixed; its note says how.

**Bugs and security problems.** All fixed 2026-10-07.

| | Problem | Where |
|---|---|---|
| ~~**P4-1**~~ | ~~A deleted account can't self-heal from CSV or zip export. It gets `200` with an empty body instead of the `409`, because the headers and BOM go out before the account lookup throws.~~ **Fixed 2026-10-07.** `ExportService.requireAccount()` runs before any header. The frontend had a second gap: `api()`'s `raw` branch threw before the dead-session check, so it now falls through to the shared error path. `DeadSessionRecoveryIntegrationTest` covers all three export routes. | `ExportController` / `ExportService`, `js/api.js` |
| ~~**P4-2**~~ | ~~Editing an activity without `caloriesBurned` silently **recalculates and overwrites** the figure the user typed. This happens even on a notes-only edit, and the value becomes `null` if the profile has no weight.~~ **Fixed 2026-10-07.** An omitted figure is now kept, unless type or duration changed and the stored figure was itself calculated. A new `recalculateCalories` flag re-derives on request, and a failed re-derivation keeps the old figure instead of nulling it. The edit modal used to send the pre-filled figure back every time, which pinned it as typed. It now sends a figure only when one was changed, and asks for a recalculation when the field is blank. Covered by `ActivityServiceEditCaloriesTest` (10) and an end-to-end case in `ActivityControllerIntegrationTest`. | `ActivityService.updateActivity`, `js/panels/activities.js` |
| ~~**P4-3**~~ | ~~Changing a password doesn't revoke refresh tokens, although reset-password does. Separately, one login revokes every other device's refresh token.~~ **Fixed 2026-10-07.** Issuing a token no longer revokes the others. That had run on every refresh as well as every login, so one tab renewing its session signed out every other device. A refresh now retires only the token it was given, through an atomic conditional `UPDATE` so two racing refreshes can't both win. A password change revokes all tokens and returns a fresh pair, which the profile panel stores. A daily job deletes spent tokens, since every login now adds a row. Covered by `SessionRevocationIntegrationTest` (4) and `RefreshTokenServiceTest` (4). **Still open:** access tokens are never checked server-side, so after a change or a reset an access token already issued works for up to 48h. Closing that needs a per-user token version checked in `JwtAuthenticationFilter`. | `ProfileService`, `RefreshTokenService`, `js/panels/profile.js` |
| ~~**P4-4**~~ | ~~Challenges don't check friendship, despite `FriendshipService.areFriends()` saying they do. `"Opponent not found: <id>"` confirms which user ids exist, and h2h reveals any user's full name from their id.~~ **Fixed 2026-10-07: challenges are friends-only** (the user's call; the UI had been deliberately built to allow strangers). Every invitee must be an accepted friend, and a non-friend and a non-existent id get the same 400. h2h answers only for a friend or a past opponent; a stranger and a non-existent id get the same 403, with no name. The "Athlete ID" paste box and the Profile panel's copyable Athlete ID are gone, since friends are made by email. Covered by 8 cases in `ChallengeServiceTest` and two in `GroupChallengeIntegrationTest`. | `ChallengeService`, `index.html`, `js/panels/social.js` |
| ~~**P4-5**~~ | ~~`/api/friends/**`, `/api/challenges/**` and `/api/notifications/**` are in no rate-limit tier. That includes the email-based friend lookup.~~ **Fixed 2026-10-07.** The real cause was that `tierFor()` was an allowlist, so goals, health, pain, profile, plans and stats were uncapped too. It now fails closed: every `/api/**` route is at least `STANDARD` (100/min per user). A new `INVITE` tier (10/min per user, `app.rate-limit.invite`) covers `POST /api/friends/request` and `POST /api/challenges`, the two requests that notify someone else. Covered by 5 new cases in `RateLimitingFilterTest`; two that asserted routes were unlimited were rewritten. | `RateLimitingFilter.tierFor` |
| ~~**P4-6**~~ | ~~A pain log can be linked to **another user's** activity, because `activityId` has no ownership check.~~ **Fixed 2026-10-07.** Worse than listed: `fk_pain_log_activity` has no ON DELETE rule, so a linked activity couldn't be deleted at all (a `409`). A stranger's link could pin your workout forever, and your own links blocked your own deletes. `logPain` now ownership-checks (`403`) and rejects an unknown id (`400`) instead of silently dropping it. `deleteActivity` unlinks pain logs first, so the pain history survives the workout. Covered by `PainLogIntegrationTest` (4); 3 failed on the old code. | `PainAnalysisService.logPain`, `ActivityService.deleteActivity` |
| ~~**P4-7**~~ | **Fixed 2026-10-07.** All five are now a `400` that names the problem. New `PasswordPolicy.requireHashable()` caps passwords at 72 bytes in UTF-8 (not characters) on register, change and reset; reset checks it before the token so a link isn't used up. A missing activity `type`, pain `bodyPart` or recommendation `activityId` is rejected up front, and pattern detection skips old entries with a null `bodyPart`. `/auth/refresh` answers 401 only for the service's own refusals; anything else is rethrown as a logged `500` instead of a 401 leaking its internal message. Covered by `BadInputIntegrationTest` (8, all failing on the old code) and `AuthControllerRefreshTest` (3). Original: ~~Some inputs return 500 instead of 400: a password over 72 bytes (BCrypt), an activity with no `type`, `recommendation/generate` with no `activityId`, and pain patterns when an entry has a null `bodyPart`. `/auth/refresh` can also return 500 when an exception has a null message (`Map.of`).~~ | auth, `ActivityService`, `RecommendationService`, `PainAnalysisService`, `AuthController` |

**Wrong numbers.**

| | Problem | Where |
|---|---|---|
| ~~**P4-8**~~ | ~~`/stats/monthly` `weekOverWeekCalorieChange` compares a 30-day total against a 7-day window, so it runs about 4× high. `prevFrom` is computed and never used.~~ **Fixed 2026-10-07.** Worse than "4× high": a steady month read as +300%, and a drop came out positive. It now compares the last 7 days with the 7 before them; the windows don't overlap at the boundary. With no calories the week before, it is `null` (shown as `—`) instead of a false `"+0.0%"`. Formatting is pinned to `Locale.ROOT`, so the decimal separator is always `.`. Covered by `StatsServiceMonthlyTest` (4, all failing on the old code). | `StatsService.getMonthlyStats` |
| ~~**P4-9**~~ | ~~The CSV formula guard also prefixes negative numbers (`'-0.6`), which turns numeric cells into text in a spreadsheet.~~ **Fixed 2026-10-07.** A value that is a Java `Number` is never prefixed, since it can't hold a formula. Free text is still guarded even when it looks numeric, because a note reading `-1+1` would evaluate. Covered by two new cases in `ExportServiceTest`. | `ExportService` |
| ~~**P4-10**~~ | ~~The digest's `avgNetPerLoggedDay` divides the week's whole training burn by the number of days with food logged.~~ **Fixed 2026-10-07.** Intake was already averaged over the logged days, but the burn was the whole week's training divided by that count, so a workout on an unlogged day was charged against the logged ones. It now sums the burn on the logged days only. In the new test the old code gave -1286 where the right answer is -986. Covered by one new case in `WeeklyDigestServiceTest`. | `WeeklyDigestService` |
| ~~**P4-11**~~ | ~~h2h against yourself returns a negative `otherWinners`.~~ **Fixed with P4-4 (2026-10-07):** h2h against yourself is a 400. | `ChallengeService.getH2HStats` |
| ~~**P4-12**~~ | ~~Every `PUT /health/metrics` writes a weigh-in row, even with an empty body, and stores `0` for any unknown value.~~ **Fixed 2026-10-07.** An empty body is a `400` and writes nothing (after the account check, so a dead session still gets its `409`). A value the profile doesn't have is stored as `null`, not `0`; the columns were already nullable, so there is no migration, and the readers already skipped zero weights. The frontend never calls this endpoint, so nothing changed there. Rows already stored with `0` are left as they are. Covered by `BodyMetricsServiceInputTest` (5, three failing on the old code). | `BodyMetricsService.updateMetrics` |

**Behaviour that surprises.**

| | Problem |
|---|---|
| ~~**P4-13**~~ | ~~Omitting `accept` on challenge respond **declines** the invite (primitive `boolean`).~~ **Fixed 2026-10-08.** `accept` is a `Boolean` and `null` is a `400` that leaves the invite unanswered. Declining is permanent, so guessing "no" was the worst default. The frontend's buttons always send `true` or `false`, so nothing changed there. Covered by a new case in `GroupChallengeIntegrationTest` (an empty body, a body with only `userId`, and an explicit `null`; the first failed before). |
| ~~**P4-14**~~ | ~~A challenge settled as CANCELLED ("nobody else joined") hides its `resultMessage`, and nobody is told.~~ **Fixed 2026-10-08.** There were three faults, not one. The API returned `resultMessage` only for `COMPLETED`; the card showed only the bare word "CANCELLED"; and the notification, which *was* sent, was titled "Your challenge ended in a draw", because "no winner" was read as "draw". The map now carries the message for a challenge cancelled with no contest (and no `winner`), the card shows it, and the notification is titled "Your challenge was cancelled". A challenge its creator cancelled has no message and is unchanged. Covered by 3 new cases in `ChallengeServiceTest` (two failed before). |
| ~~**P4-15**~~ | ~~The one-open-challenge-per-pair rule still blocks a pair after one of them declined or left a challenge that is still running.~~ **Fixed 2026-10-08.** The rule asked whether the opponent was ever listed on a live challenge. Declining and leaving both set `DECLINED`, so walking away didn't free you. New `Challenge.isStillIn()` (not `DECLINED`), checked for both the caller and the invitee: an unanswered invite still blocks, as it should. The caller side matters too, since someone who left their own running challenge is equally out of it. Covered by 4 new cases in `ChallengeServiceTest` (two failed before). |
| ~~**P4-16**~~ | ~~A meal `PUT` never derives calories from macros, unlike `POST`, so a meal whose calories are cleared with `clear` shows no kcal.~~ **Fixed 2026-10-08.** `updateMeal` now calls the same `fillCaloriesFromMacros` that logging does, so clearing the calories of a meal with all three macros recalculates them. It only fills a gap: a figure that is there is never replaced, because it may be one the person typed (the same provenance limit as P4-2). With only some macros there is nothing to derive from, exactly as when logging. Side effect, wanted: a meal an earlier edit left with macros and no kcal is repaired by its next save. Covered by 5 new cases in `NutritionServiceTest` (three failed before). |
| ~~**P4-17**~~ | ~~These inputs aren't validated: pain `severity`, water `amountMl` (negatives are accepted), `hoursSlept`, activity `difficultyRating` on create, and profile weight, height and age.~~ **Fixed 2026-10-08.** Each is a `400` that names the limit, from one `InputChecks` class: weight 20-500 kg, height 50-275 cm, age 5-120, severity 1-5, water 1-5000 ml, sleep more than 0 and at most 24 h, ratings 1-5. The limits are wide on purpose: they refuse what can't be true, not what is merely unusual. Beyond the list: registration and `PUT /health/metrics` write the same fields, so they get the same limits; an omitted water amount, sleep hours or severity arrives as `0` and is now refused rather than logged; and **sleep quality was silently clamped** into 1-5, so an omitted one was saved as a 1 and the client never told. It is now refused. A rejected profile update saves nothing. Covered by `InputValidationIntegrationTest` (8, all failing before) and `InputChecksTest` (4). **Not covered:** `duration` and the other activity fields, free-text lengths, and the date fields. |
| ~~**P4-18**~~ | ~~Several reads skip the user lookup, so a dead session gets an empty result instead of the `409` self-heal: metrics history, sleep analysis and history, pain history and patterns, active plan, recommendations, timing, plateau, and both personal-best endpoints.~~ **Fixed 2026-10-08.** The list was incomplete: a test that discovers every `GET` route from Spring and calls each with a deleted account's token found **21** offenders, not about 10. It also caught goals, friends (3), challenges (2), recovery, the notification unread count and the digest (2). New `AccountGuard` (`requireSelf` / `requireCurrent`) sits beside the identity check in each controller and throws the `409` when the account is gone. It is in the controllers rather than the services so those services' unit tests are untouched. `DeadSessionReadsIntegrationTest` first proves no route rejects a live account, then deletes the account and expects the `409` from every route, so a new endpoint is covered the moment it exists. **Not covered:** write routes; most already look the user up. |
| ~~**P4-19**~~ | ~~Unbounded or unordered lists: `recommendation/user` (not paged, no order), `challenges/user` (no `ORDER BY`), and a plan's `days` (no `@OrderBy`).~~ **Fixed 2026-10-08.** `recommendation/user` is paged and newest first, on the pattern the other history lists use (negative page is 0, size capped at 100). **This changes its response from an array to a page**, so `coach.js` changed with it: the AI panel gets a pager, and the overview teaser asks for just the newest (`size=1`) instead of downloading the history to sort it. `challenges/user` is `ORDER BY createdAt DESC`. A plan's `days` is `@OrderBy("dayNumber")`; the keys are random UUIDs, so before it the order was shuffled. Also found by the test, not on the list: `getPlanDetail` returned its lazy `days` after the transaction ended, which only worked because production runs `open-in-view=true` (the test profile turns it off, and there the endpoint was a 500). It now loads the days inside the transaction. Covered by `ListOrderingIntegrationTest` (4, all failing before). |
| **P4-20** | Leftovers: the `PERSONAL_BEST` notification type is never emitted. `JacksonConfig` builds a Jackson 2 mapper while Boot 4.1 serializes with Jackson 3, so it may be inert (unverified). `enroll` requires a JSON body it ignores. |

---

# Suggested order

**P0 to P3 are done, and so is the security half of P4.** What remains, in order:

1. ~~**The wrong numbers: P4-8, P4-9, P4-10, P4-12.**~~ All done 2026-10-07.
2. ~~**P4-18 and P4-17**~~ Done 2026-10-08.
3. **The remaining surprises: ~~P4-13~~ to ~~P4-19~~, P4-20.**
4. **Not in the table, but open:**
   - Access tokens are never checked server-side, so one already issued keeps working for
     up to 48h after a password change or reset (`PROGRESS.md` Phase 12). It needs a
     per-user token version checked in `JwtAuthenticationFilter`.
   - P4-1's dead-session export path has not been driven in a browser.
   - **Deployment.** `frontend/js/config.js` still holds the placeholder Render URL. Render
     also needs `FRONTEND_URL` and the `MAIL_*` variables.

Done earlier:

1. ~~**Re-run the full suite.**~~ Done 2026-09-25 — 437 passing, 0 failures.
2. ~~**Drive Phase 9 in a real browser.**~~ Done 2026-09-25 in Chrome on real MySQL; five bugs found and fixed (`PROGRESS.md` Phase 9).
3. ~~**P3-2 (`API.md`)**~~ Done 2026-09-25.
4. ~~**P3-3 (pagination)**~~ Done 2026-09-25.
5. ~~**P4-1 to P4-7**~~ Done 2026-10-07 (`PROGRESS.md` Phases 12 and 14).

The original ordering notes follow.

---

**P0 and P1 are both done.** What remains is P2 and P3.

1. **P2-1 (nutrition)** if FitPilot is meant for real training — it is the one gap that
   makes a whole category of goal impossible. `GoalType` is still activity-side and
   weekly only, so no goal can be about body weight, which is what most people are
   here for.
2. **P2-3 (broader AI use)** if it is a portfolio piece — Gemini is still called from
   exactly two places, and a weekly digest reasoning over the combined record is where
   the "AI-powered" claim is actually earned.
3. **P2-2 (delivery layer)** is worth pulling forward now that P1-4 has brought a mail
   transport into the stack — the email-digest half of it is no longer blocked.
4. **P2-5 (data export)** is still the cheapest thing in this document.
5. **P3** whenever. P3-2 (`API.md`) is the one with the widest audience, and P3-4
   (undated activity rows) has since been fixed in passing — rows carry a date now.

Nothing is left over from P0 or P1. Both of the loose ends P0 left — no service reading
`additionalMetrics`, and personal bests tracking only duration and calories — were
closed by P1-1.
