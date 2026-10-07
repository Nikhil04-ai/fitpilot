# API.md — FitPilot REST API Reference

Every endpoint the backend serves: **83 operations** across 18 route groups. Written
2026-09-25 by tracing each controller through its service to the objects Jackson actually
serializes, and checked against the live OpenAPI spec so nothing is missing or invented.

Swagger UI at `/swagger-ui/index.html` is still the place to *try* a call. It can't show
most response shapes, though, because many endpoints return a `Map`. This file does.

**When you change an endpoint, change its entry here in the same commit.** A reference
that drifts from the code does more harm than having none.

## Contents

- [Conventions](#conventions) — auth, identity, errors, pagination, rate limits, dates
- [Auth](#auth) · [Profile](#profile) · [Activities](#activities) · [Exercises](#exercises) ·
  [Personal bests](#personal-bests) · [Stats](#stats) · [Recovery](#recovery)
- [Nutrition](#nutrition) · [Goals](#goals) · [Health](#health) · [Pain log](#pain-log) ·
  [Workout plans](#workout-plans) · [Recommendations](#recommendations)
- [Friends](#friends) · [Challenges](#challenges) · [Notifications](#notifications) ·
  [Weekly digest](#weekly-digest) · [Export](#export)

---

## Conventions

### Base URL and format

| Environment | Base URL |
|---|---|
| Local | `http://localhost:8080` |
| Deployed | the Render service URL (`render.yaml`) |

Requests and responses are JSON (UTF-8), except the export downloads. Send
`Content-Type: application/json` on any request with a body.

**Dates and times** are ISO-8601 with no time zone: a `LocalDate` is `"2026-09-25"`, a
`LocalDateTime` is `"2026-09-25T13:00:00"`. Both are in server-local time.

### Authentication

1. `POST /api/auth/register` creates the account. It does not log you in.
2. `POST /api/auth/login` returns an `accessToken` and a `refreshToken`.
3. Send the access token on every other call: `Authorization: Bearer <accessToken>`.
4. When a call returns **401**, send the refresh token to `POST /api/auth/refresh` and retry
   once with the new access token.

| Token | Lifetime | Notes |
|---|---|---|
| Access (JWT) | 48 hours | Stateless. The subject is the user id. |
| Refresh | 30 days | Stored server-side, one per signed-in device, and **rotated**: every refresh revokes the one it was given and no other. Logout, a password change and a password reset revoke all of them. Revoked and expired tokens are deleted daily at 03:45. |

**Public routes** (no token needed): `/api/auth/**`, `/actuator/health`, `/swagger-ui/**`
and `/v3/api-docs/**`. Everything else requires a valid access token.

### Identity: whose data a call touches

The caller is **always the user in the token**. A client-supplied id never decides it
(`AGENT.md`, `CLAUDE.md` rule 1).

- **`{userId}` in a path** must be the caller's own id, or the call returns
  `403 {"error":"You can only access your own data."}`. It's there for readable URLs; it
  never selects another account.
- **Resources loaded by their own id** (an activity, meal, goal, notification, challenge,
  friendship) are ownership- or membership-checked, and return **403** when they aren't
  yours.
- **A `userId` field in a request body is ignored.** Several request types still accept one
  so older clients don't break, and the server overwrites it with the caller's id.
- **Roles are server-assigned.** Registration has no `role` field.

### Status codes

| Code | Meaning here |
|---|---|
| `200` | Success. Most writes return the created or updated object rather than `201`. |
| `204` | Success with no body: deletes, and `GET /api/digest` when there is no digest yet. |
| `400` | The request was understood and refused: validation, an unreadable body, a bad enum value, a business rule. **A resource id that doesn't exist is also a 400** (for example `"Meal not found: …"`), not a 404. |
| `401` | No token, or an expired or invalid one. Refresh and retry. |
| `403` | Valid token, but not your data. Refreshing won't help, so don't retry. |
| `404` | Only for a route that doesn't exist: `{"error":"That endpoint doesn't exist."}` |
| `409` | The token is valid but its account is gone, or a uniqueness conflict. See below. |
| `429` | Rate limited. See [Rate limits](#rate-limits). |
| `500` | A real fault. The body is always the generic `{"error":"Something went wrong on our end. Please try again."}` and the detail goes to the server log only. |

**401 and 403 are deliberately different.** The frontend refreshes on 401 and gives up on
403. Spring's default returns 403 for both, which once silently broke session renewal
(`CLAUDE.md` rule 2).

**The account-gone 409.** A token can outlive its user row, for example after the
database is reset. Any call made with such a token returns:

```json
{ "error": "Your session refers to an account that no longer exists in the database. You've been signed out — please log in again." }
```

Clients should treat this exact 409 as "clear the session and show login". The frontend
matches on the phrase `account that no longer exists`.

Every `GET` route that depends on who is asking returns it, and an automated test finds
those routes from the running app and checks each one. Only routes whose answer is the
same for everyone are exempt: `GET /api/plans`, `GET /api/plans/{planId}` and
`GET /api/export/datasets`. Write routes (`POST`, `PUT`, `DELETE`) are not covered by that
test; they return it where they look the user up, which most do.

### Error bodies

Every error goes through `GlobalExceptionHandler` and has one of two shapes:

```json
{ "error": "Rating must be between 1 and 5" }
```

```json
{ "password": "Password must be at least 8 characters", "email": "Invalid email" }
```

The second shape is used only for `@Valid` field failures, which today means registration:
one key per invalid field. A body that can't be parsed (malformed JSON, or an enum value
that doesn't exist, such as `"mealType":"BRUNCH"`) returns
`400 {"error":"The request couldn't be read. Check the field names and value types and try again."}`.

Messages from a `400` are written for people and are safe to show in the UI. Raw
database and Hibernate messages never reach the client.

### Pagination

Paged endpoints take `page` (0-based, default `0`) and `size` (default `20`, clamped to
`1–100`), and return Spring Data's stable `VIA_DTO` shape:

```json
{
  "content": [ { "...": "one item per row" } ],
  "page": { "size": 20, "number": 0, "totalElements": 42, "totalPages": 3 }
}
```

The paged endpoints are:
- `GET /api/activities`
- `GET /api/nutrition/meals`
- `GET /api/notifications`
- `GET /api/pain-log/{userId}`
- `GET /api/health/sleep/{userId}/history`
- `GET /api/health/metrics/{userId}/history`

The last three were unbounded lists until P3-3. On every paged endpoint a negative
`page` is treated as `0`. `activities` and `meals` also take a `sort` parameter,
documented with each endpoint.

### Rate limits

Per-instance token buckets that refill each minute. Limits are set in
`application.properties` and can be tightened without a code change.

| Tier | Routes | Limit | Keyed by |
|---|---|---|---|
| Auth | `/api/auth/**` | 10 / min | client IP (there is no account to key on yet) |
| AI | `/api/activities/natural`, `/api/nutrition/meals/natural`, `/api/recommendation/generate`, `/api/digest/generate` | 10 / min | user id (IP if no valid token) |
| Invite | `POST /api/friends/request` and `POST /api/challenges` (the two requests that notify someone else) | 10 / min, shared between the two | user id (IP if no valid token) |
| Standard | **every other `/api/**` route** | 100 / min | user id (IP if no valid token) |

Paths outside `/api` (`/actuator/health`, Swagger) are not rate-limited. Each tier has its
own bucket, so a spent invite budget doesn't block reading or answering requests.

- Limited responses carry `X-RateLimit-Limit` and `X-RateLimit-Remaining` headers.
- When a bucket is empty, the response is `429` with `Retry-After: 60` and a tier-specific
  message, for example `{"error":"Too many AI requests. Try again shortly."}`.
- Behind a proxy, the client IP is read from the first `X-Forwarded-For` entry.

### AI-backed endpoints

Four endpoints call Google Gemini:
- `POST /api/activities/natural`
- `POST /api/nutrition/meals/natural`
- `POST /api/recommendation/generate`
- `POST /api/digest/generate`

The model is set by `GEMINI_MODEL` (default `gemini-flash-lite-latest`). Without a key,
or when the call fails, each one degrades rather than erroring:
- The two free-text parsers return a `400` whose message says which case it is: "isn't
  set up on this server" (no key) or "the AI service didn't respond" (the call failed).
- Recommendations and the digest fall back to rule-based output, flagged in the response
  (`aiGenerated: false` on the digest).

---

## Auth
Account creation, sign-in, token renewal, sign-out, and password recovery for users who are not signed in. Every route here is public (`/api/auth/**` is `permitAll`), and the whole prefix is in the **auth** rate-limit tier.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/register` | Create an account |
| POST | `/api/auth/login` | Exchange email + password for an access/refresh token pair |
| POST | `/api/auth/refresh` | Exchange a refresh token for a new pair (rotation) |
| POST | `/api/auth/logout` | Revoke the caller's refresh tokens |
| POST | `/api/auth/forgot-password` | Email a password-reset link |
| POST | `/api/auth/reset-password` | Set a new password using a reset token |

### `POST /api/auth/register`
Creates a `USER`-role account. It does not sign the user in, because no tokens are returned. Call `/login` next.
- **Auth:** `Public`
- **Rate limit:** auth tier
- **Body:**
  ```json
  {
    "email": "asha@example.com",
    "password": "correct-horse-battery",
    "firstName": "Asha",
    "lastName": "Rao",
    "weight": 68.5,
    "height": 172.0,
    "age": 29
  }
  ```
  - `email` (required, valid email) and `password` (required, at least 8 characters) are checked with `@Valid`. All other fields are optional.
  - `weight` (kg, 20 to 500), `height` (cm, 50 to 275) and `age` (5 to 120) are optional, and refused when outside their range, with the same messages as `PUT /api/profile/{userId}`. Weight is what lets the server estimate calories for activities later, so leaving it out means `caloriesBurned` stays `null` unless the client sends it.
  - The email is trimmed and lower-cased before it is stored.
  - There is no `role` field. A `role` sent by the client is ignored, and every new account is `USER`.
- **Response:** `200`, a `UserResponse`. This is the same shape as `GET /api/profile/{userId}`. `currentStreak` and `longestStreak` both start at `0`.
  ```json
  {
    "id": "3f6c2a9e-8d1b-4c3e-9a51-0b7e2f4d6a11",
    "email": "asha@example.com",
    "firstName": "Asha",
    "lastName": "Rao",
    "weight": 68.5,
    "height": 172.0,
    "age": 29,
    "currentStreak": 0,
    "longestStreak": 0,
    "createdAt": "2026-09-25T08:14:03.512",
    "updatedAt": "2026-09-25T08:14:03.512"
  }
  ```
- **Errors:**
  - `400` `{"email":"Email is required"}` / `{"email":"Invalid email"}` / `{"password":"Password is required"}` / `{"password":"Password must be at least 8 characters"}`. These are validation errors, so they use the field-map shape.
  - `400` "An account with this email already exists. Try logging in instead."
  - `400` "Password is too long: keep it to 72 characters or fewer (fewer if it uses accented letters or emoji)." The password is over 72 bytes in UTF-8, which BCrypt can't hash. This used to be a `500`.
  - `409` "That value is already in use. Please choose a different one." This only happens when two registrations with the same email race each other.

### `POST /api/auth/login`
Checks the credentials and returns a 48-hour access token and a 30-day refresh token.
- **Auth:** `Public`
- **Rate limit:** auth tier
- **Body:**
  ```json
  { "email": "asha@example.com", "password": "correct-horse-battery" }
  ```
  Both fields are needed. The email is matched case-insensitively, with a fallback to the exact typed form for older accounts. This endpoint does not use `@Valid`, so a missing field is reported as a failed login, not as a validation error.
- **Response:** `200`
  ```json
  {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIzZjZj...",
    "refreshToken": "9b2e4c1a-7f3d-4e8b-a6c5-2d1f0e9b8a77",
    "expiresIn": "48 hours",
    "user": { "id": "3f6c2a9e-...", "email": "asha@example.com", "firstName": "Asha", "lastName": "Rao",
              "weight": 68.5, "height": 172.0, "age": 29, "currentStreak": 4, "longestStreak": 11,
              "createdAt": "2026-09-01T10:02:44", "updatedAt": "2026-09-24T19:30:12" }
  }
  ```
  - `expiresIn` is a human-readable label, not a number of seconds.
  - `refreshToken` is a UUID string.
  - Each login adds a session. Refresh tokens issued to the account's other devices stay valid.
- **Errors:** `401` "Invalid email or password". This is returned for an unknown email, a wrong password and a missing field alike.

### `POST /api/auth/refresh`
Exchanges a valid refresh token for a new access token and a new refresh token. The token sent in is revoked (rotation). The account's other refresh tokens are not affected.
- **Auth:** `Public` (the refresh token is the credential)
- **Rate limit:** auth tier
- **Body:**
  ```json
  { "refreshToken": "9b2e4c1a-7f3d-4e8b-a6c5-2d1f0e9b8a77" }
  ```
  `refreshToken` is required.
- **Response:** `200`. There is no `user` object here, unlike `/login`.
  ```json
  {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIzZjZj...",
    "refreshToken": "c47d0f2e-1a9b-4b6e-8e3f-5a2c7d9e0b14",
    "expiresIn": "48 hours",
    "refreshTokenExpiresIn": "30 days"
  }
  ```
- **Errors:** all of these are `401` (not 400):
  - "Invalid refresh token." The token is unknown or missing.
  - "Refresh token has been revoked. Please log in again." The token was already rotated (including by a concurrent refresh with the same token), or the user logged out or changed their password.
  - "Refresh token has expired. Please log in again."

  Any other failure during a refresh is a real fault and returns the generic `500`. It used to come back as a `401` showing the internal error message.

### `POST /api/auth/logout`
Revokes **all** refresh tokens for the caller. The caller is identified by the Bearer token if one is sent, otherwise by owning the refresh token in the body.
- **Auth:** `Public`, with an optional `Bearer` header. At least one of the Bearer token or `refreshToken` is needed for anything to be revoked on the server.
- **Rate limit:** auth tier
- **Body (optional):**
  ```json
  { "refreshToken": "9b2e4c1a-7f3d-4e8b-a6c5-2d1f0e9b8a77" }
  ```
  - The body can be left out entirely.
  - A `userId` in the body is ignored. It used to be trusted, which let anyone log anyone else out.
  - When a valid Bearer token is present, the body is not read at all.
- **Response:** `200` in every case. A logout always "succeeds", so the client should clear its storage whatever the outcome.
  ```json
  { "message": "Logged out successfully. All sessions revoked." }
  ```
  If no caller could be identified, the response is `{"message":"Logged out locally. No active session to revoke."}`.
  - The access token is a stateless JWT and **stays valid until it expires**. Only refresh tokens are revoked.
- **Errors:** none specific.

### `POST /api/auth/forgot-password`
Starts a password reset. If the address belongs to an account, the server emails a single-use reset link.
- **Auth:** `Public`
- **Rate limit:** auth tier (keyed by IP)
- **Body:**
  ```json
  { "email": "asha@example.com" }
  ```
  - The email is matched case-insensitively.
  - A blank or missing email is accepted and does nothing.
- **Response:** `200` with the same body whether or not the account exists, so this endpoint cannot be used to discover which emails have accounts.
  ```json
  { "message": "If an account exists for that address, a reset link is on its way." }
  ```
  - The link has the form `<FRONTEND_URL>/index.html?reset=<token>`, and the token is 43 URL-safe Base64 characters.
  - The link lasts `app.password-reset.ttl-minutes`, which defaults to 30 minutes.
  - Requesting a new link cancels any link that is still outstanding.
  - The email is sent through Brevo or Resend (`MAIL_PROVIDER`). With no `MAIL_API_KEY` configured, the link is written to the server log instead. If a provider is configured and delivery fails, the link is **not** logged, because it is a password equivalent. See README > Password reset email.
- **Errors:** none specific. Every well-formed request gets the `200` above.

### `POST /api/auth/reset-password`
Sets a new password using a reset token, then revokes every refresh token for the account.
- **Auth:** `Public` (the reset token is the credential)
- **Rate limit:** auth tier
- **Body:**
  ```json
  { "token": "Qm9vS3...43-char-url-safe-token", "newPassword": "a-new-long-password" }
  ```
  Both fields are required. The minimum password length comes from `app.password-reset.min-length` and defaults to 8.
- **Response:** `200`
  ```json
  { "message": "Password updated. You have been signed out everywhere — log in with your new password." }
  ```
  The caller is not signed in afterwards. Call `/login` with the new password.
- **Errors:** all of these are `400`. The length check runs before the token lookup.
  - "This reset link is not valid. Request a new one." The token is blank or missing.
  - "Your new password must be at least 8 characters." The number reflects the configured minimum.
  - "Password is too long: keep it to 72 characters or fewer (fewer if it uses accented letters or emoji)." The new password is over 72 bytes. Like the minimum, this is checked before the token, so the link isn't used up.
  - "This reset link has expired or already been used. Request a new one." This single message covers unknown, used and expired tokens, so the response does not reveal which case applies.

---

## Profile
Reads and edits the signed-in user's own account record. `{userId}` must be the caller's own id (see the `{userId}` convention).

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/profile/{userId}` | Get your profile |
| PUT | `/api/profile/{userId}` | Update name, weight, height, age |
| PUT | `/api/profile/{userId}/password` | Change password (needs the current one) |

### `GET /api/profile/{userId}`
Returns the caller's profile.
- **Auth:** `Bearer`
- **Params:** `userId` (path, string UUID). It must equal the id in the caller's JWT.
- **Response:** `200`, a `UserResponse`. This has the same fields as `/register`. The password hash is never included.
  ```json
  {
    "id": "3f6c2a9e-8d1b-4c3e-9a51-0b7e2f4d6a11",
    "email": "asha@example.com",
    "firstName": "Asha",
    "lastName": "Rao",
    "weight": 68.5,
    "height": 172.0,
    "age": 29,
    "currentStreak": 4,
    "longestStreak": 11,
    "createdAt": "2026-09-01T10:02:44",
    "updatedAt": "2026-09-24T19:30:12"
  }
  ```
  `currentStreak` and `longestStreak` count consecutive training days, and the server keeps them up to date.
- **Errors:** none specific.

### `PUT /api/profile/{userId}`
Updates some profile fields and leaves the others as they are.
- **Auth:** `Bearer`
- **Params:** `userId` (path). It must be the caller's own id.
- **Body:**
  ```json
  { "firstName": "Asha", "lastName": "Rao", "weight": 67.9, "height": 172.0, "age": 30 }
  ```
  - Every field is optional. Anything left out or set to `null` keeps its current value.
  - A blank `firstName` or `lastName` is also ignored, so a name cannot be cleared.
  - `weight` is in kg (20 to 500), `height` in cm (50 to 275) and `age` in years (5 to 120). A value outside its range is refused, and nothing in the request is saved.
  - `email` and `password` cannot be changed here. Any other field sent is ignored.
- **Response:** `200`, the updated `UserResponse` (the same shape as `GET` above).
- **Errors:** `400` "Weight must be between 20 and 500 kg." / "Height must be between 50 and 275 cm." / "Age must be between 5 and 120 years."

### `PUT /api/profile/{userId}/password`
Changes the password after checking the current one.
- **Auth:** `Bearer`
- **Params:** `userId` (path). It must be the caller's own id.
- **Body:**
  ```json
  { "currentPassword": "correct-horse-battery", "newPassword": "a-new-long-password" }
  ```
  Both fields are required. The current password is checked before the new one's length.
- **Response:** `200`
  ```json
  {
    "message": "Password changed successfully. Other devices have been signed out.",
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIzZjZj...",
    "refreshToken": "c47d0f2e-1a9b-4b6e-8e3f-5a2c7d9e0b14"
  }
  ```
  - Like `/api/auth/reset-password`, this **revokes every refresh token** for the account, including the caller's.
  - The response carries a fresh pair so the caller stays signed in. Clients should replace their stored tokens with it.
  - Access tokens already issued are stateless and stay valid until they expire (up to 48 hours).
- **Errors:**
  - `400` "Current password is incorrect." This also covers a missing `currentPassword`.
  - `400` "New password must be at least 8 characters."
  - `400` "Password is too long: keep it to 72 characters or fewer (fewer if it uses accented letters or emoji)." The new password is over 72 bytes. A *current* password over 72 bytes is reported as "Current password is incorrect."

---

## Activities
Logging, listing, editing and deleting workouts, from a form or from free text parsed by Gemini. Activities are always read from and written to the caller's own account. Any `userId` a client sends is ignored, and an `{activityId}` owned by someone else is rejected with `403`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/activities` | Paged workout history, newest first |
| POST | `/api/activities` | Log a workout |
| PUT | `/api/activities/{activityId}` | Edit a workout (partial update) |
| DELETE | `/api/activities/{activityId}` | Delete a workout |
| POST | `/api/activities/{activityId}/rate` | Set difficulty rating 1–5 and get a progression suggestion |
| PUT | `/api/activities/{activityId}/notes` | Replace a workout's notes |
| POST | `/api/activities/natural` | Log a workout from a plain-English description (AI) |

**`ActivityType`** can be `RUNNING`, `WALKING`, `CYCLING`, `SWIMMING`, `WEIGHT_TRAINING`, `YOGA`, `HIIT`, `CARDIO`, `STRETCHING` or `OTHER`. Any other value returns `400` "The request couldn't be read. Check the field names and value types and try again."

### `GET /api/activities`
Lists the caller's workouts, one page at a time.
- **Auth:** `Bearer`
- **Rate limit:** standard tier
- **Params:**
  - `page` (int, default `0`). A negative value is treated as `0`.
  - `size` (int, default `20`). It is clamped to between 1 and 100.
  - `sort` (string, default `createdAt,desc`), in the form `<property>[,asc|desc]`.
    - The allowed properties are `createdAt`, `updatedAt`, `startTime`, `duration`, `caloriesBurned` and `difficultyRating`.
    - Any other property silently falls back to `createdAt`.
    - Any direction other than `asc` means descending.
- **Response:** `200`. The response is paged (see Pagination), and each item in `content` is an `ActivityResponse` (full shape under `POST /api/activities`).
  - On this endpoint `isPersonalBest` is **worked out per row**. It is `true` when the activity currently holds its type's duration, calorie or distance record.
  - Each `exercises[].isPersonalBest` is also filled in.
  - The fields that describe what happened when the workout was saved are always `null` here: `personalBestMessage`, `muscleRecoveryWarnings`, `comebackPlan`, `goalAchievements`, `progressionSuggestion` and `exercisePersonalBests`.
- **Errors:** none specific.

### `POST /api/activities`
Logs a workout. The response also reports what the save triggered: streaks, records, recovery warnings, a comeback plan and goal progress.
- **Auth:** `Bearer`
- **Rate limit:** standard tier
- **Body:**
  ```json
  {
    "type": "WEIGHT_TRAINING",
    "duration": 55,
    "caloriesBurned": 320,
    "startTime": "2026-09-24T18:30:00",
    "distanceKm": null,
    "difficultyRating": 4,
    "notes": "Felt strong on bench",
    "additionalMetrics": { "avgHeartRate": 128 },
    "exercises": [
      { "name": "Bench Press", "sets": 3, "reps": 8, "weightKg": 60.0 },
      { "name": "Pull-up", "sets": 3, "reps": 10, "weightKg": null }
    ],
    "userId": "ignored"
  }
  ```
  - `type` is **required**. Without it the response is `400` "An activity needs a type, such as RUNNING or WEIGHT_TRAINING."
  - Every other field is optional.
  - `duration` is in minutes.
  - If `startTime` is left out, it defaults to now. It is a local date-time with no zone.
  - `caloriesBurned`: if it is left out, the server estimates it from `type`, `duration` and the profile weight using MET values. It stays `null` if either the duration or the profile weight is missing.
  - `recalculateCalories` is for edits only and is ignored here.
  - `distanceKm` is in km. If it is left out, the server reads `additionalMetrics.distance_km` or `additionalMetrics.distanceKm` instead. A number or a numeric string is accepted there.
  - `additionalMetrics` is a free-form JSON object that is stored as given.
  - `exercises`:
    - Entries with a blank `name` are skipped.
    - The order of the entries is kept.
    - A `null` `weightKg` means a bodyweight movement.
  - `difficultyRating` is optional, and must be 1 to 5 when given (`400` "Rating must be between 1 and 5"), as on edit.
  - `userId` is ignored. The activity is always saved to the caller's account.
- **Response:** `200`
  ```json
  {
    "id": "a1d9c6f2-3b7e-4e0a-9c85-6f1b2d4e7a90",
    "userId": "3f6c2a9e-8d1b-4c3e-9a51-0b7e2f4d6a11",
    "type": "WEIGHT_TRAINING",
    "additionalMetrics": { "avgHeartRate": 128 },
    "duration": 55,
    "caloriesBurned": 320,
    "startTime": "2026-09-24T18:30:00",
    "completedAt": "2026-09-24T19:25:00",
    "difficultyRating": 4,
    "notes": "Felt strong on bench",
    "distanceKm": null,
    "exercises": [
      { "id": "e7...", "name": "Bench Press", "sets": 3, "reps": 8, "weightKg": 60.0, "orderIndex": 0,
        "volume": 1440.0, "estimatedOneRepMax": 76.0, "isPersonalBest": true },
      { "id": "e8...", "name": "Pull-up", "sets": 3, "reps": 10, "weightKg": null, "orderIndex": 1,
        "volume": null, "estimatedOneRepMax": null, "isPersonalBest": true }
    ],
    "totalVolume": 1440.0,
    "createdAt": "2026-09-24T19:26:10.104",
    "updatedAt": "2026-09-24T19:26:10.104",
    "currentStreak": 5,
    "longestStreak": 11,
    "isPersonalBest": true,
    "personalBestMessage": "New weight training record — longest at 55 min.",
    "muscleRecoveryWarnings": ["FULL_BODY needs 22 more hours of rest."],
    "comebackPlan": null,
    "goalAchievements": null,
    "progressionSuggestion": null,
    "exercisePersonalBests": ["Bench Press — 60kg x 8, est. 1RM 76kg, 1440kg total volume"]
  }
  ```
  - `completedAt` is `startTime + duration`.
  - `volume` is sets × reps × weightKg. It is `null` unless all three are present.
  - `estimatedOneRepMax` is calculated with the Epley formula. It is `null` above 12 reps or when no weight is given.
  - `totalVolume` is the sum of the exercise volumes, rounded to 0.1. It is `null` when no exercise has a load.
  - `isPersonalBest` refers to the activity type's record (duration, calories or distance). The first workout of a given type always counts as one, with the message "First <type> session logged — that's a benchmark to beat."
  - `comebackPlan` is only present after a gap of 14 or more days:
    ```json
    { "comebackDetected": true, "daysAway": 18, "recommendedIntensity": "40%", "weekLabel": "Week 1",
      "message": "Welcome back! You've been away 18 days. Starting at 40% of your previous intensity to prevent injury. Gradually increase over the next 3 weeks." }
    ```
  - `muscleRecoveryWarnings`, `goalAchievements` and `exercisePersonalBests` are lists of ready-to-show strings. Each is `null` when empty, not `[]`.
  - `progressionSuggestion` is always `null` on create. It is only filled in by `/rate`.
- **Errors:** none specific. An unknown `type` value gives the common 400 described above.

### `PUT /api/activities/{activityId}`
Edits a workout. After the edit, the server rebuilds streaks, personal bests and recovery from all remaining activities.
- **Auth:** `Bearer`
- **Rate limit:** standard tier
- **Params:** `activityId` (path, string UUID). The activity must belong to the caller.
- **Body:** the same fields as `POST /api/activities`, all optional. A field left out or set to `null` keeps its current value.
  ```json
  { "duration": 60, "startTime": "2026-09-24T18:00:00", "exercises": [] }
  ```
  - `exercises` distinguishes `null` from `[]`. `null` leaves the movements alone, and `[]` deletes all of them. A non-empty list replaces the whole list.
  - `recalculateCalories` (boolean, edit only) asks for calories to be re-derived from type, duration and profile weight, replacing the stored figure.
  - What happens to `caloriesBurned`, in order:
    1. If `caloriesBurned` is sent, it is stored as-is, and `recalculateCalories` is ignored.
    2. If `recalculateCalories` is `true`, the figure is re-derived.
    3. If `type` or `duration` changed, the figure is re-derived, but only when the stored figure was itself calculated. That means it was `null`, or it equals what the calculator gives for the old type and duration at the current weight. A figure the user typed is kept.
    4. Otherwise the stored figure is kept. A `notes`-only edit never touches calories.

    A re-derivation that can't be done, because the profile has no weight, keeps the stored figure instead of setting it to `null`.
  - `distanceKm` is only updated when `distanceKm` or `additionalMetrics` is sent.
  - `completedAt` is always recalculated.
  - `difficultyRating` is range-checked here (1–5).
- **Response:** `200`, an `ActivityResponse`. On this endpoint `isPersonalBest` is always `false`, and all the save-event fields are `null`.
- **Errors:**
  - `400` "Activity not found: <activityId>". A missing activity gets a 400, not a 404.
  - `400` "Rating must be between 1 and 5"
  - `403` "You don't have access to this activity."

### `DELETE /api/activities/{activityId}`
Deletes a workout and its exercises, then rebuilds streaks, personal bests and recovery. Pain logs linked to the workout are kept, with their link cleared.
- **Auth:** `Bearer`
- **Rate limit:** standard tier
- **Params:** `activityId` (path). The activity must belong to the caller.
- **Response:** `204 No Content`, with an empty body.
- **Errors:**
  - `400` "Activity not found: <activityId>"
  - `403` "You don't have access to this activity."

### `POST /api/activities/{activityId}/rate`
Sets how hard the workout felt and returns a progression suggestion based on recent sessions.
- **Auth:** `Bearer`
- **Rate limit:** standard tier
- **Params:** `activityId` (path). The activity must belong to the caller.
- **Body:**
  ```json
  { "rating": 2 }
  ```
  `rating` is required and must be an integer from 1 to 5. If it is left out, it becomes `0` and is rejected.
- **Response:** `200`, an `ActivityResponse` with `progressionSuggestion` filled in when there is something to say. Otherwise `progressionSuggestion` is `null`.
  - When the workout has loaded exercises, the suggestion is based on the load, for example "Across your last 3 sessions: Bench Press is up 8%. Keep the current progression going."
  - Otherwise it is based on the last 3 rated sessions of the same type, for example "Your last 3 running sessions all felt easy. Consider increasing intensity, distance, or duration."
  - `isPersonalBest` is always `false` here.
- **Errors:**
  - `400` "Rating must be between 1 and 5"
  - `400` "Activity not found: <activityId>"
  - `403` "You don't have access to this activity."

### `PUT /api/activities/{activityId}/notes`
Replaces the workout's free-text notes.
- **Auth:** `Bearer`
- **Rate limit:** standard tier
- **Params:** `activityId` (path). The activity must belong to the caller.
- **Body:**
  ```json
  { "notes": "Left knee a bit sore on the last set" }
  ```
  - `notes` can be up to 1000 characters, which is the column length.
  - `null` or a missing field clears the notes.
  - Nothing is recalculated.
- **Response:** `200`, an `ActivityResponse`. `isPersonalBest` is `false`, and the save-event fields are `null`.
- **Errors:**
  - `400` "Activity not found: <activityId>"
  - `403` "You don't have access to this activity."

### `POST /api/activities/natural`
Parses a plain-English description with Gemini and logs the result exactly as `POST /api/activities` would.
- **Auth:** `Bearer`
- **Rate limit:** AI tier
- **Body:**
  ```json
  { "description": "Bench 3x8 at 60kg then squats 5x5 at 80kg, about 50 minutes", "userId": "ignored" }
  ```
  - `description` is the only field the server reads. It is not checked for blank.
  - `userId` is accepted so that older clients keep working, but it is ignored.
- **Response:** `200`, an `ActivityResponse` (the same shape as `POST /api/activities`, including the save-event fields).
  - `startTime` is always the time of the request, and calories are always estimated by the server.
  - The movements and distance come from what the model extracted. If the model returns an unknown activity type, `type` falls back to `OTHER`.
  - `additionalMetrics` holds whatever the model returned, typically `{"distance_km": null, "notes": "..."}`. The top-level `notes` is not filled in.
- **Errors:**
  - `400` "Natural language logging isn't set up on this server (no GEMINI_API_KEY). Log it with the form instead."
  - `400` "Couldn't read that activity — the AI service didn't respond. Try again in a moment, or log it with the form." This covers a timeout or output that could not be parsed.

---

## Exercises
Strength records for each movement, built from the `exercises` logged on activities.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/exercises/personal-bests/{userId}` | Best lift, best estimated 1RM, best volume and best bodyweight reps for each movement |

### `GET /api/exercises/personal-bests/{userId}`
Returns one record per movement the caller has logged, sorted by `bestEstimatedOneRepMax`, highest first.
- **Auth:** `Bearer`
- **Params:** `userId` (path). It must be the caller's own id.
- **Response:** `200`, an array of `ExercisePersonalBest` entities. It is `[]` when nothing has been logged.
  ```json
  [
    {
      "id": "5c0e...",
      "normalisedName": "bench press",
      "displayName": "Bench Press",
      "bestWeightKg": 65.0,
      "bestWeightReps": 5,
      "bestEstimatedOneRepMax": 76.0,
      "bestVolume": 1560.0,
      "bestBodyweightReps": null,
      "achievedAt": "2026-09-24T19:26:10",
      "createdAt": "2026-09-02T18:40:00",
      "updatedAt": "2026-09-24T19:26:10"
    },
    {
      "id": "8a41...",
      "normalisedName": "pull-up",
      "displayName": "Pull-up",
      "bestWeightKg": null, "bestWeightReps": null, "bestEstimatedOneRepMax": null, "bestVolume": null,
      "bestBodyweightReps": 12,
      "achievedAt": "2026-09-20T07:10:00",
      "createdAt": "2026-09-05T07:00:00",
      "updatedAt": "2026-09-20T07:10:00"
    }
  ]
  ```
  - Records are matched on `normalisedName` (lower-cased, with spaces collapsed).
  - `displayName` is the spelling from the entry that most recently set a record.
  - `bestWeightReps` is the number of reps done at `bestWeightKg`.
  - `bestBodyweightReps` is only counted from sets with no load. For bodyweight movements it is the only field filled in.
  - Records with a `null` 1RM sort last on MySQL.
- **Errors:** none specific.

---

## Personal bests
Records for each activity type: the longest session, the most calories and the furthest distance.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/personal-bests/{userId}` | One record row per activity type trained |

### `GET /api/personal-bests/{userId}`
Returns the caller's record for each activity type they have logged.
- **Auth:** `Bearer`
- **Params:** `userId` (path). It must be the caller's own id.
- **Response:** `200`, an array of `PersonalBest` entities with at most one per `ActivityType`, in no guaranteed order. It is `[]` when nothing has been logged.
  ```json
  [
    {
      "id": "b72f...",
      "activityType": "RUNNING",
      "bestDuration": 48,
      "bestCalories": 540,
      "bestDistanceKm": 10.2,
      "achievedAt": "2026-09-21T07:45:12",
      "createdAt": "2026-08-30T06:55:00",
      "updatedAt": "2026-09-21T07:45:12"
    }
  ]
  ```
  - `bestDuration` is in minutes and `bestCalories` in kcal.
  - Each of the three `best*` values is tracked separately, so they can come from different sessions.
  - Any of them can be `null` if it was never recorded.
  - `achievedAt` is the last time any of the three improved.
- **Errors:** none specific.

---

## Stats
Totals and analysis worked out from the caller's activities. Every route takes the caller's own `{userId}`. The time windows are rolling windows ending at the moment of the request, based on `startTime` and server-local time.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/stats/all-time/{userId}` | Lifetime totals |
| GET | `/api/stats/daily/{userId}` | Per-day series for charts |
| GET | `/api/stats/monthly/{userId}` | Last-30-days totals plus a calorie-change figure |
| GET | `/api/stats/weekly/{userId}` | Last-7-days totals |
| GET | `/api/stats/timing/{userId}` | Which hour of day you train most (last 30 days) |
| GET | `/api/stats/plateau/{userId}` | Plateau check for one activity type (last 4 weeks) |

### `GET /api/stats/all-time/{userId}`
Returns lifetime totals, added up by the database.
- **Auth:** `Bearer`
- **Params:** `userId` (path). It must be the caller's own id.
- **Response:** `200`. Key order is not guaranteed.
  ```json
  {
    "totalWorkouts": 87,
    "totalCaloriesBurned": 26450,
    "totalDurationMinutes": 4120,
    "currentStreak": 5,
    "longestStreak": 11,
    "personalBestsCount": 4
  }
  ```
  `personalBestsCount` is the number of activity types that have a record (rows in `/api/personal-bests`), not the number of records broken.
- **Errors:** none specific.

### `GET /api/stats/daily/{userId}`
Returns one row per calendar day, oldest first, ending today. Days with nothing logged are included as zeros.
- **Auth:** `Bearer`
- **Params:**
  - `userId` (path). It must be the caller's own id.
  - `days` (query, int, default `30`). It is clamped to between 1 and 365. The response always has exactly that many rows.
- **Response:** `200`
  ```json
  [
    { "date": "2026-09-23", "calories": 0,   "minutes": 0,  "workouts": 0 },
    { "date": "2026-09-24", "calories": 640, "minutes": 95, "workouts": 2 },
    { "date": "2026-09-25", "calories": 310, "minutes": 40, "workouts": 1 }
  ]
  ```
  - `date` is an ISO `yyyy-MM-dd` string.
  - An activity with `null` calories or `null` duration still counts towards `workouts`.
- **Errors:** `400` "The request couldn't be read. Check the field names and value types and try again." This is returned when `days` is not an integer.

### `GET /api/stats/monthly/{userId}`
Returns totals for the last 30 days, plus `weekOverWeekCalorieChange`.
- **Auth:** `Bearer`
- **Params:** `userId` (path). It must be the caller's own id.
- **Response:** `200`. Key order is not guaranteed.
  ```json
  {
    "totalCalories": 8120,
    "totalDurationMinutes": 1240,
    "activityCount": 24,
    "mostFrequentActivityType": "RUNNING",
    "currentStreak": 5,
    "longestStreak": 11,
    "fromDate": "2026-08-26T09:12:40.118",
    "toDate": "2026-09-25T09:12:40.118",
    "weekOverWeekCalorieChange": "+12.4%"
  }
  ```
  - `mostFrequentActivityType` is the name of the most common `ActivityType`, or `"N/A"` if there are no activities.
  - `fromDate` and `toDate` are ISO local date-time strings.
  - `weekOverWeekCalorieChange` is a signed percentage string with one decimal and a `.` separator. It compares the calories of the last 7 days with the 7 days before them.
  - It is `null` when that earlier week had no calories, since no percentage can be given. The Insights panel shows `—`.
- **Errors:** none specific.

### `GET /api/stats/weekly/{userId}`
Returns totals for the last 7 days.
- **Auth:** `Bearer`
- **Params:** `userId` (path). It must be the caller's own id.
- **Response:** `200`. The fields are the same as `/monthly` without `weekOverWeekCalorieChange`.
  ```json
  {
    "totalCalories": 1850,
    "totalDurationMinutes": 290,
    "activityCount": 6,
    "mostFrequentActivityType": "WEIGHT_TRAINING",
    "currentStreak": 5,
    "longestStreak": 11,
    "fromDate": "2026-09-18T09:12:40.118",
    "toDate": "2026-09-25T09:12:40.118"
  }
  ```
- **Errors:** none specific.

### `GET /api/stats/timing/{userId}`
Finds the hour of day the caller most often starts workouts, over the last 30 days.
- **Auth:** `Bearer`
- **Params:** `userId` (path). It must be the caller's own id.
- **Response:** `200`, in one of two shapes. When there is data:
  ```json
  {
    "peakWorkoutHour": 7,
    "peakTimeWindow": "07:00–08:00",
    "activitiesAnalysed": 18,
    "completionRateAtPeak": "44.4%",
    "suggestion": "Your data shows you complete 44% of workouts between 07:00–08:00. Consider scheduling all workouts in this window.",
    "hourlyDistribution": [0,0,0,0,0,0,2,8,1,0,0,0,1,0,0,0,0,2,3,1,0,0,0,0]
  }
  ```
  - `peakWorkoutHour` runs from 0 to 23.
  - `hourlyDistribution` always has 24 entries, where index = hour of `startTime`.
  - Despite its name, `completionRateAtPeak` is the percentage of workouts that fall in the peak hour.
  - If two hours tie for the peak, which one is picked is arbitrary.

  When there are no activities in the window:
  ```json
  { "message": "Not enough data yet. Log more workouts to see your peak time." }
  ```
  Clients should check for `message` before reading `peakWorkoutHour`.
- **Errors:** none specific.

### `GET /api/stats/plateau/{userId}`
Checks whether average calories per session for one activity type have stalled. It compares the last 14 days with the 14 days before that.
- **Auth:** `Bearer`
- **Params:**
  - `userId` (path). It must be the caller's own id.
  - `activityType` (query, **required**, an `ActivityType` enum value such as `RUNNING`).
- **Response:** `200`, in one of two shapes. When both windows have enough data:
  ```json
  {
    "activityType": "RUNNING",
    "recentAverageCalories": "412.5",
    "previousAverageCalories": "405.0",
    "improvementPercent": "1.9%",
    "plateauDetected": true,
    "recommendation": "Your running performance has plateaued. Try increasing intensity, duration, or switching workout styles to break through."
  }
  ```
  - `plateauDetected` is `true` when the improvement is **at most 5%**, which includes a decline.
  - `recommendation` is only present when `plateauDetected` is `true`.
  - The averages and the percentage are returned as strings.

  When there is not enough data:
  ```json
  { "status": "insufficient_data", "message": "Not enough activities to detect a plateau yet." }
  ```
  - This is returned when either window has fewer than 2 sessions of that type.
  - The message is "No calorie data to compare." when the sessions exist but none of them have calories.
- **Errors:**
  - `400` "Request failed." `activityType` is missing.
  - `400` "The request couldn't be read. Check the field names and value types and try again." `activityType` is not a valid enum value.

---

## Recovery
When each muscle group was last trained, so that the client can show which groups are still recovering.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/recovery/status/{userId}` | Last-worked time for every muscle group trained so far |

### `GET /api/recovery/status/{userId}`
Returns one row per muscle group the caller has ever trained.
- **Auth:** `Bearer`
- **Params:** `userId` (path). It must be the caller's own id.
- **Response:** `200`, an array of `MuscleRecovery` entities in no guaranteed order. It is `[]` before any activity has been logged.
  ```json
  [
    { "id": "d3a0...", "muscleGroup": "QUADRICEPS", "lastWorkedAt": "2026-09-24T18:30:05", "recommendedRestHours": 48, "updatedAt": "2026-09-24T18:30:05" },
    { "id": "d3a1...", "muscleGroup": "FULL_BODY",  "lastWorkedAt": "2026-09-23T07:02:44", "recommendedRestHours": 48, "updatedAt": "2026-09-23T07:02:44" }
  ]
  ```
  - `muscleGroup` can be `QUADRICEPS`, `HAMSTRINGS`, `CALVES`, `CORE`, `GLUTES`, `SHOULDERS`, `BACK`, `ARMS`, `LEGS`, `CHEST` or `FULL_BODY`.
  - **There is no "recovered" flag and no hours-remaining field.** The client works it out as `lastWorkedAt + recommendedRestHours` against now. `recommendedRestHours` is currently always 48.
  - Groups come from a fixed mapping of activity type to groups, and the exercises logged are not taken into account. For example, `WEIGHT_TRAINING`, `YOGA`, `HIIT`, `STRETCHING` and `OTHER` all mark only `FULL_BODY`, and `CHEST` is never set.
  - How `lastWorkedAt` is set depends on the last change:
    - When an activity is **logged**, it is the moment the activity was saved, not its `startTime`. A backdated workout therefore counts as trained now.
    - After an activity is **edited or deleted**, it is rebuilt from the latest surviving `startTime` for each group. The value can therefore move after an unrelated edit.
- **Errors:** none specific.

---

## Nutrition
Meal logging (manual or parsed from free text by Gemini), daily intake-versus-burn summaries and the net-calorie series. Every route takes the user from the JWT. There is no `{userId}` segment, and a `userId` in the body is ignored.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/nutrition/meals` | Log a meal with explicit numbers |
| POST | `/api/nutrition/meals/natural` | Log a meal from a free-text description (AI-estimated) |
| PUT | `/api/nutrition/meals/{mealId}` | Partially update a meal |
| DELETE | `/api/nutrition/meals/{mealId}` | Delete a meal |
| GET | `/api/nutrition/meals` | Paged meal history |
| GET | `/api/nutrition/summary` | One day's intake, macros, exercise burn and net calories |
| GET | `/api/nutrition/daily` | Per-day intake/burn/net series for the chart |

### `POST /api/nutrition/meals`
Logs a meal with numbers the user entered.
- **Auth:** `Bearer`
- **Rate limit:** standard tier.
- **Body:**
  ```json
  {
    "description": "Oats with banana and peanut butter",
    "mealType": "BREAKFAST",
    "calories": 420,
    "proteinGrams": 14.0,
    "carbsGrams": 62.5,
    "fatGrams": 12.0,
    "loggedAt": "2026-09-25T08:15:00"
  }
  ```
  - Every field is optional, but the meal must have `calories > 0` or at least one macro.
  - `mealType` is one of `BREAKFAST`, `LUNCH`, `DINNER`, `SNACK`, `OTHER`. Defaults to `OTHER`.
  - `loggedAt` is when the meal was eaten, as a local ISO date-time with no zone. It defaults to now. Daily totals, the series and goal periods all group by this field, so a backdated meal counts on the day it was eaten.
  - If `calories` is omitted but all three macros are present, calories are derived at 4/4/9 kcal per gram. An explicit `calories` value is never overwritten.
  - `description` is trimmed and silently truncated to 500 characters.
  - `userId` and `clear` are ignored.
- **Response:** `200`
  ```json
  {
    "id": "5b0c1f7e-3a8d-4c1e-9d2a-0f6b7e8a9c10",
    "userId": "a1b2c3d4-...",
    "description": "Oats with banana and peanut butter",
    "mealType": "BREAKFAST",
    "calories": 420,
    "proteinGrams": 14.0,
    "carbsGrams": 62.5,
    "fatGrams": 12.0,
    "loggedAt": "2026-09-25T08:15:00",
    "createdAt": "2026-09-25T08:16:02.113",
    "updatedAt": "2026-09-25T08:16:02.113",
    "aiEstimated": false,
    "goalAchievements": ["Goal reached! PROTEIN PER DAY target of 120 g achieved."],
    "parseNote": null
  }
  ```
  - `goalAchievements` lists the nutrition goals whose state this meal changed, and is `null` when there are none. An `AT_MOST` budget produces a warning instead of a congratulation, for example `"Over your CALORIE INTAKE PER DAY budget — 150 kcal above 2000."`.
  - `parseNote` is always `null` on this route.
- **Errors:** `400` `"A meal needs at least a calorie figure or its macros."` · `400` `"Calories cannot be negative."`

### `POST /api/nutrition/meals/natural`
Sends a description like "two eggs, toast and a coffee" to Gemini and logs the estimated meal.
- **Auth:** `Bearer`
- **Rate limit:** AI tier.
- **Body:**
  ```json
  { "description": "two scrambled eggs, a slice of toast and a flat white" }
  ```
  - `description` is required. It is the only field read; any other field, including `loggedAt`, is ignored.
  - The meal is always logged at the current time and cannot be backdated here. To change the time afterwards, use `PUT`.
  - Gemini picks `mealType` and falls back to `OTHER` when unsure.
- **Response:** `200`, the same `MealResponse` shape as `POST /meals`, with:
  - `aiEstimated: true`
  - `parseNote: "Estimated from your description — tap to correct it."`
- **Errors:**
  - `400` `"Describe what you ate."` when `description` is blank or missing.
  - `400` `"Meal parsing isn't set up on this server (no GEMINI_API_KEY) — enter the meal manually."`
  - `400` `"Couldn't estimate that meal — the AI service didn't respond. Try again in a moment, or enter it manually."` when a key is set but the call failed.
  - `400` `"Couldn't work out the calories for that. Try naming the quantity, or enter the meal manually."`

### `PUT /api/nutrition/meals/{mealId}`
Partially updates one of the caller's meals.
- **Auth:** `Bearer`
- **Rate limit:** standard tier.
- **Params:** `mealId` (path, string UUID)
- **Body:**
  ```json
  {
    "description": "Oats with banana",
    "mealType": "BREAKFAST",
    "calories": 380,
    "proteinGrams": 12.0,
    "carbsGrams": null,
    "fatGrams": null,
    "loggedAt": "2026-09-25T07:45:00",
    "clear": ["fatGrams"]
  }
  ```
  - All fields are optional. A `null` or omitted field keeps its current value.
  - `clear` lists fields to set to empty. Allowed names: `description`, `calories`, `proteinGrams`, `carbsGrams`, `fatGrams`. When a field is in `clear`, that wins over any value sent for it. `mealType` and `loggedAt` cannot be cleared.
  - `aiEstimated` is reset to `false` only when a number (`calories` or a macro) actually changes value. Resending the same numbers, or editing only the description, type or time, keeps the flag.
  - Unlike `POST`, an update never derives calories from the macros.
  - `userId` is ignored.
- **Response:** `200`, the `MealResponse` shape, with `goalAchievements` recomputed.
- **Errors:**
  - `400` `"Meal not found: <mealId>"`
  - `403` `"You don't have access to this meal."`
  - `400` `"Can't clear '<field>' on a meal."`
  - `400` `"A meal needs at least a calorie figure or its macros."` when the edit would leave the meal empty.
  - `400` `"Calories cannot be negative."`

### `DELETE /api/nutrition/meals/{mealId}`
Deletes one of the caller's meals and recomputes today's nutrition goals.
- **Auth:** `Bearer`
- **Rate limit:** standard tier.
- **Params:** `mealId` (path, string UUID)
- **Response:** `204`, no body.
- **Errors:** `400` `"Meal not found: <mealId>"` · `403` `"You don't have access to this meal."`

### `GET /api/nutrition/meals`
Returns the caller's meal history, newest first by default.
- **Auth:** `Bearer`
- **Rate limit:** standard tier.
- **Params:**
  - `page` (int, default `0`, negative treated as 0)
  - `size` (int, default `20`, clamped to 1–100)
  - `sort` (string, default `loggedAt,desc`): `<property>[,asc|desc]`, where property is one of `loggedAt`, `createdAt`, `calories`. An unknown property silently falls back to `loggedAt`. Any direction other than `asc` is treated as `desc`.
- **Response:** `200`, paged `MealResponse` (see Pagination). `goalAchievements` and `parseNote` are always `null` in the list.
- **Errors:** none specific. A negative `page` is treated as `0`.

### `GET /api/nutrition/summary`
Returns one day's intake alongside that day's exercise burn and estimated maintenance.
- **Auth:** `Bearer`
- **Rate limit:** standard tier.
- **Params:** `date` (query, ISO date `YYYY-MM-DD`, optional, default today)
- **Response:** `200`
  ```json
  {
    "date": "2026-09-25",
    "mealsLogged": 3,
    "caloriesIn": 1840,
    "proteinGrams": 96.5,
    "carbsGrams": 210.0,
    "fatGrams": 61.2,
    "caloriesBurnedExercise": 350,
    "basalCalories": 1680,
    "maintenanceCalories": 2016,
    "netCalories": -526,
    "balance": "DEFICIT",
    "estimateNote": "Maintenance is estimated from your height, weight and age, and is sex-neutral because your profile doesn't record one — treat it as ±100 kcal.",
    "byMealType": { "BREAKFAST": 420, "LUNCH": 680, "DINNER": 740 },
    "meals": [ { "id": "…", "mealType": "BREAKFAST", "calories": 420, "aiEstimated": false, "…": "full MealResponse" } ]
  }
  ```
  - `netCalories` = `caloriesIn − maintenanceCalories − caloriesBurnedExercise`.
  - `balance` is `DEFICIT`, `SURPLUS` or `MAINTENANCE`.
  - Macro totals are rounded to one decimal place.
  - `byMealType` has keys only for meal types that have a calorie total that day.
  - `meals` are in `loggedAt` order, oldest first.
  - If the profile lacks weight, height or age, then `basalCalories`, `maintenanceCalories`, `netCalories` and `balance` are all `null`, and `estimateNote` reads `"Add your weight, height and age in Profile to see whether you're in a deficit or a surplus."`.
- **Errors:** A malformed `date` returns the generic unreadable-request `400`.

### `GET /api/nutrition/daily`
Returns intake, exercise burn and net calories for each of the last N days, for the net-calorie chart.
- **Auth:** `Bearer`
- **Rate limit:** standard tier.
- **Params:** `days` (int, default `30`, clamped to 1–365)
- **Response:** `200`. The array is oldest first, ends today, and has an entry for every day, including days with nothing logged.
  ```json
  [
    { "date": "2026-09-24", "caloriesIn": 0, "caloriesBurnedExercise": 0, "maintenanceCalories": 2016, "netCalories": null },
    { "date": "2026-09-25", "caloriesIn": 1840, "caloriesBurnedExercise": 350, "maintenanceCalories": 2016, "netCalories": -526 }
  ]
  ```
  - `netCalories` is `null` on a day with no intake logged, so an unrecorded day is not charted as a deficit.
  - `netCalories` is also `null` on every day when maintenance can't be estimated.
  - `maintenanceCalories` is the same value on every row, based on the current profile.

---

## Goals
User-defined targets with period-aware progress. Activity goals accumulate from workouts, nutrition goals are recomputed from the meal log, and the body-weight goal reads the latest weigh-in.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/goals` | Create a goal |
| GET | `/api/goals/{userId}` | List goals with computed progress |
| PUT | `/api/goals/{goalId}` | Update a goal's target and/or deadline |
| DELETE | `/api/goals/{goalId}` | Delete a goal |

**Goal types.** The period and direction of each type are fixed by its `GoalType` value and cannot be set by the client.

| `type` | period | direction | unit | fed by |
|---|---|---|---|---|
| `CALORIES_PER_WEEK` | `WEEKLY` | `AT_LEAST` | kcal | activity `caloriesBurned` |
| `WORKOUTS_PER_WEEK` | `WEEKLY` | `AT_LEAST` | workouts | +1 per activity |
| `DURATION_PER_WEEK` | `WEEKLY` | `AT_LEAST` | min | activity `duration` |
| `CALORIE_INTAKE_PER_DAY` | `DAILY` | `AT_MOST` | kcal | sum of the day's meal calories |
| `PROTEIN_PER_DAY` | `DAILY` | `AT_LEAST` | g | sum of the day's meal protein |
| `TARGET_WEIGHT` | `NONE` | `TOWARDS` | kg | latest body-metrics weigh-in (falls back to profile weight) |

- `WEEKLY` goals reset on Monday and `DAILY` goals at midnight. `NONE` never resets.
- `AT_LEAST` goals complete when `currentValue >= targetValue`.
- `AT_MOST` goals are budgets. They are **never** `isCompleted`; they report `isExceeded` instead.
- `TOWARDS` goals measure from `startValue`, the weight when the goal was created. If no weight existed then, the first weigh-in afterwards becomes `startValue`. The goal is complete when the target is reached from either direction, cutting or bulking.
- A goal whose deadline has passed without completion is expired. Its values are frozen and it stops accumulating.

### `POST /api/goals`
Creates a goal for the caller.
- **Auth:** `Bearer`
- **Body:**
  ```json
  { "type": "TARGET_WEIGHT", "targetValue": 72.0, "deadline": "2026-12-31" }
  ```
  - `type` is required and must be one of the six values above.
  - `targetValue` is required and must be greater than 0.
  - `deadline` (ISO date) is optional.
  - A `userId` in the body is ignored.
- **Response:** `200`, the raw `Goal` entity:
  ```json
  {
    "id": "0e9f…",
    "type": "TARGET_WEIGHT",
    "targetValue": 72.0,
    "currentValue": 80.0,
    "deadline": "2026-12-31",
    "isCompleted": false,
    "startValue": 80.0,
    "periodStart": null,
    "createdAt": "2026-09-25T10:02:11.402",
    "updatedAt": "2026-09-25T10:02:11.415"
  }
  ```
  - `startValue` is `null` for every type except `TARGET_WEIGHT`.
  - `periodStart` is Monday for weekly goals, today for daily goals and `null` for `TARGET_WEIGHT`.
  - Nutrition and weight goals are filled in immediately. An activity goal starts at `0.0`.
  - This shape is **not** the progress map returned by `GET`: there is no `progressPercent`, `unit` and so on.
- **Errors:**
  - `400` `"A goal needs a type."`
  - `400` `"A goal target must be greater than zero."` This also applies when `targetValue` is omitted, because it defaults to 0.
  - An unknown `type` returns the generic unreadable-request `400`.

### `GET /api/goals/{userId}`
Lists all of the caller's goals with computed progress. Before responding, it rolls periods over and refreshes nutrition and weight goals from their sources.
- **Auth:** `Bearer`
- **Params:** `userId` (path, must be the caller)
- **Response:** `200`. This is an array of maps built with `HashMap`, so key order is not stable.
  ```json
  [
    {
      "id": "3c1d…",
      "type": "CALORIE_INTAKE_PER_DAY",
      "period": "DAILY",
      "direction": "AT_MOST",
      "unit": "kcal",
      "targetValue": 2000.0,
      "currentValue": 2150.0,
      "startValue": null,
      "deadline": null,
      "isCompleted": false,
      "isExpired": false,
      "isExceeded": true,
      "daysRemaining": null,
      "weekStarting": "2026-09-25",
      "progressPercent": 100.0,
      "remaining": 0.0,
      "exceededBy": 150.0
    },
    {
      "id": "0e9f…",
      "type": "TARGET_WEIGHT",
      "period": "NONE",
      "direction": "TOWARDS",
      "unit": "kg",
      "targetValue": 72.0,
      "currentValue": 76.0,
      "startValue": 80.0,
      "deadline": "2026-12-31",
      "isCompleted": false,
      "isExpired": false,
      "isExceeded": false,
      "daysRemaining": 97,
      "weekStarting": null,
      "progressPercent": 50.0
    }
  ]
  ```
  - `remaining` and `exceededBy` are present **only** on `AT_MOST` goals. `remaining` never goes below 0, and any overshoot is reported in `exceededBy`.
  - `isExceeded` is only ever `true` on `AT_MOST` goals.
  - `progressPercent` is `current/target × 100`, capped at 100. For `TOWARDS` goals it is distance covered from `startValue` towards the target: 0 when moving the wrong way or when `startValue` is unknown, and 100 when start equals target.
  - `weekStarting` is the current period start: Monday for weekly goals, today for daily goals, `null` for `TARGET_WEIGHT`. It carries this name for daily goals too.
  - `daysRemaining` is negative once the deadline has passed and `null` when there is no deadline.

### `PUT /api/goals/{goalId}`
Updates a goal's target and/or deadline, then re-evaluates `isCompleted`.
- **Auth:** `Bearer`
- **Params:** `goalId` (path, string UUID)
- **Body:**
  ```json
  { "targetValue": 70.0, "deadline": "2027-01-31" }
  ```
  - Both fields are optional, and `null` keeps the current value.
  - A deadline cannot be removed once set.
  - `type` cannot be changed.
- **Response:** `200`, the raw `Goal` entity, same shape as `POST`.
- **Errors:**
  - `400` `"Goal not found: <goalId>"`
  - `403` `"You don't have access to this goal."`
  - `400` `"A goal target must be greater than zero."`

### `DELETE /api/goals/{goalId}`
Deletes one of the caller's goals.
- **Auth:** `Bearer`
- **Params:** `goalId` (path, string UUID)
- **Response:** `204`, no body.
- **Errors:** `400` `"Goal not found: <goalId>"` · `403` `"You don't have access to this goal."`

---

## Health
Body metrics (weight, height, BMI) with weigh-in history, daily water intake, and sleep logging with weekly analysis and a sleep-versus-training correlation.

| Method | Path | Purpose |
|---|---|---|
| PUT | `/api/health/metrics/{userId}` | Record weight/height and log a weigh-in |
| GET | `/api/health/metrics/{userId}` | Current BMI plus the latest 100 weigh-ins |
| GET | `/api/health/metrics/{userId}/history` | Paged full weigh-in history |
| POST | `/api/health/water/{userId}` | Log a water intake |
| GET | `/api/health/water/{userId}/today` | Today's hydration summary |
| GET | `/api/health/water/{userId}/week` | Per-day water totals for the last 7 days |
| POST | `/api/health/sleep/{userId}` | Log (upsert) a night's sleep |
| GET | `/api/health/sleep/{userId}/analysis` | 7-day sleep averages, history and insights |
| GET | `/api/health/sleep/{userId}/performance` | Sleep vs. next-day training, with correlation |
| GET | `/api/health/sleep/{userId}/history` | Paged sleep history |

Every `{userId}` path variable must be the caller's own id.

### `PUT /api/health/metrics/{userId}`
Updates the profile's weight and/or height, writes a weigh-in row, and refreshes `TARGET_WEIGHT` goals.
- **Auth:** `Bearer`
- **Params:** `userId` (path)
- **Body:**
  ```json
  { "weight": 76.4, "height": 178.0 }
  ```
  - At least one of the two is required. A missing field keeps the profile's current value. Units are kg and cm.
  - One history row is written per successful call. A value the profile doesn't have yet is stored as `null` in that row, not `0`.
- **Response:** `200`
  ```json
  {
    "userId": "a1b2c3d4-…",
    "weight": 76.4,
    "height": 178.0,
    "age": 29,
    "bmi": 24.1,
    "bmiCategory": "Normal weight",
    "healthyWeightRange": "58.6 kg – 78.9 kg",
    "bmiNote": "Great! Maintain your healthy lifestyle.",
    "goalAchievements": ["Goal reached! Body weight target of 76 kg achieved."]
  }
  ```
  - `bmiCategory` is `Underweight`, `Normal weight`, `Overweight` or `Obese`.
  - `bmi` and `bmiCategory` are `null`, and `healthyWeightRange` and `bmiNote` are absent, unless both weight and height are known.
  - `goalAchievements` appears only when a weight goal was just reached.
  - This response has no `history`.
- **Errors:**
  - `400` "Send a weight or a height to record." Neither field was sent, and nothing is written. A dead session still gets the `409`, which is checked first.
  - `400` "Weight must be between 20 and 500 kg." / "Height must be between 50 and 275 cm." The same limits as the profile, since this writes the same two fields.

### `GET /api/health/metrics/{userId}`
Returns current body metrics plus the most recent weigh-ins for the trend chart.
- **Auth:** `Bearer`
- **Params:** `userId` (path)
- **Response:** `200`. Same keys as the `PUT` response, without `goalAchievements`, plus:
  ```json
  {
    "userId": "a1b2c3d4-…", "weight": 76.4, "height": 178.0, "age": 29,
    "bmi": 24.1, "bmiCategory": "Normal weight",
    "healthyWeightRange": "58.6 kg – 78.9 kg", "bmiNote": "Great! Maintain your healthy lifestyle.",
    "history": [
      { "weight": 80.0, "height": 178.0, "bmi": 25.2, "bmiCategory": "Overweight", "loggedAt": "2026-08-01T07:30:12.004" },
      { "weight": 76.4, "height": 178.0, "bmi": 24.1, "bmiCategory": "Normal weight", "loggedAt": "2026-09-25T07:31:40.551" }
    ],
    "historyTotal": 2
  }
  ```
  - `history` holds only the **latest 100** weigh-ins, ordered **oldest first**. Entries have no `id`.
  - `historyTotal` is the full count. When it is greater than `history.length`, fetch older entries from `/history`.

### `GET /api/health/metrics/{userId}/history`
Returns the complete weigh-in record, newest first.
- **Auth:** `Bearer`
- **Params:** `userId` (path) · `page` (int, default `0`, negative treated as 0) · `size` (int, default `20`, clamped to 1–100)
- **Response:** `200`. Paged (see Pagination). Each item is `{ "weight", "height", "bmi", "bmiCategory", "loggedAt" }`, sorted by `loggedAt` descending.

### `POST /api/health/water/{userId}`
Adds a water entry and returns that day's hydration summary.
- **Auth:** `Bearer`
- **Params:** `userId` (path)
- **Body:**
  ```json
  { "amountMl": 350, "date": "2026-09-25" }
  ```
  - `amountMl` is required and must be from 1 to 5000. An omitted value (it arrives as `0`), a negative one or a larger one is refused: `400` "Water amount must be between 1 and 5000 ml."
  - `date` (ISO date) is optional and defaults to today.
  - Each call adds a new entry; entries for a day are summed.
- **Response:** `200`, the summary for the logged `date`:
  ```json
  {
    "date": "2026-09-25",
    "totalMl": 1750.0,
    "goalMl": 2674.0,
    "progressPercent": 65.4,
    "remainingMl": 924.0,
    "goalMet": false,
    "status": "Halfway — don't forget to drink."
  }
  ```
  - `goalMl` is `max(2000, weight × 35)`, or 2000 when no weight is set.
  - `progressPercent` is capped at 100.
  - `status` is one of five fixed strings, from `"Very low — drink water now!"` (under 25%) to `"Fully hydrated! Great work."` (100%).
- **Errors:** `400` "Water amount must be between 1 and 5000 ml."

### `GET /api/health/water/{userId}/today`
Returns today's hydration summary.
- **Auth:** `Bearer`
- **Params:** `userId` (path)
- **Response:** `200`, same shape as the `POST` response, for today's date.

### `GET /api/health/water/{userId}/week`
Returns per-day water totals for the last 7 days, including today.
- **Auth:** `Bearer`
- **Params:** `userId` (path)
- **Response:** `200`, oldest first:
  ```json
  [
    { "date": "2026-09-23", "totalMl": 2100.0, "goalMl": 2674.0, "goalMet": false },
    { "date": "2026-09-25", "totalMl": 2800.0, "goalMl": 2674.0, "goalMet": true }
  ]
  ```
  - Only days with at least one entry appear. Gaps are omitted rather than returned as zeros, so the array can have fewer than 7 items, or none.

### `POST /api/health/sleep/{userId}`
Logs one night's sleep. There is one entry per `sleepDate`: logging a date again overwrites that night.
- **Auth:** `Bearer`
- **Params:** `userId` (path)
- **Body:**
  ```json
  {
    "sleepDate": "2026-09-24",
    "hoursSlept": 7.5,
    "qualityRating": 4,
    "bedtime": "2026-09-24T23:10:00",
    "wakeTime": "2026-09-25T06:40:00",
    "notes": "Woke once around 3am"
  }
  ```
  - `sleepDate` is the evening the person went to bed. It defaults to yesterday.
  - `qualityRating` is required and must be 1 to 5: `400` "Sleep quality must be between 1 and 5." It used to be clamped, so an omitted value was saved as `1`.
  - `hoursSlept` is required, more than 0 and at most 24: `400` "Hours slept must be more than 0 and at most 24."
  - `bedtime`, `wakeTime` and `notes` are optional.
  - When a date is logged again, **every** field is replaced. An omitted optional field becomes `null`; it does not keep the previous value.
- **Response:** `200`, the `SleepLog` entity:
  ```json
  {
    "id": "c7e2…",
    "sleepDate": "2026-09-24",
    "hoursSlept": 7.5,
    "qualityRating": 4,
    "bedtime": "2026-09-24T23:10:00",
    "wakeTime": "2026-09-25T06:40:00",
    "notes": "Woke once around 3am",
    "loggedAt": "2026-09-25T06:45:03.220"
  }
  ```
- **Errors:**
  - `400` "Hours slept must be more than 0 and at most 24."
  - `400` "Sleep quality must be between 1 and 5."

### `GET /api/health/sleep/{userId}/analysis`
Returns averages, history and text insights for sleep dates in the last 7 days (today − 6 through today).
- **Auth:** `Bearer`
- **Params:** `userId` (path)
- **Response:** `200`
  ```json
  {
    "weekAvgHours": 6.4,
    "weekAvgQuality": 3.2,
    "recommendation": "Try to get one more hour per night to reach the recommended 7–9 hours.",
    "history": [
      { "sleepDate": "2026-09-23", "hoursSlept": 6.0, "qualityRating": 3, "bedtime": null, "wakeTime": null, "notes": null }
    ],
    "insights": [
      "You worked out 5 days this week but slept under 7 hours. High training load needs more recovery sleep."
    ]
  }
  ```
  - `weekAvgHours` and `weekAvgQuality` are rounded to one decimal place. They are integer `0` when nothing was logged.
  - `history` entries are oldest first and have no `id` or `loggedAt`.
  - `insights` is empty when there are no logs.
  - `recommendation` is one of five fixed strings.

### `GET /api/health/sleep/{userId}/performance`
Pairs each logged night with the **next** day's training, for the scatter chart, and computes Pearson's r.
- **Auth:** `Bearer`
- **Params:** `userId` (path) · `days` (int, default `30`, clamped to 1–365). The window is sleep dates from today − (days − 1) through today.
- **Response:** `200`
  ```json
  {
    "points": [
      { "sleepDate": "2026-09-23", "workoutDate": "2026-09-24", "hoursSlept": 7.5, "qualityRating": 4, "calories": 420, "minutes": 45, "trained": true },
      { "sleepDate": "2026-09-24", "workoutDate": "2026-09-25", "hoursSlept": 6.0, "qualityRating": 3, "calories": 0, "minutes": 0, "trained": false }
    ],
    "pairedDays": 1,
    "correlation": null,
    "summary": "Log sleep on at least 4 days you also train to see whether it moves your numbers."
  }
  ```
  - Rest days (`trained: false`) are included as points with 0 calories and 0 minutes, but they are excluded from `pairedDays` and `correlation`.
  - `correlation` is rounded to 2 decimal places. It is `null` with fewer than 4 paired days, or when either variable has no variance.

### `GET /api/health/sleep/{userId}/history`
Returns sleep history, most recent night first.
- **Auth:** `Bearer`
- **Params:** `userId` (path) · `page` (int, default `0`, negative treated as 0) · `size` (int, default `20`, clamped to 1–100)
- **Response:** `200`. Paged (see Pagination) `SleepLog` entities, same shape as the `POST` response, sorted by `sleepDate` descending.

---

## Pain log
Records discomfort by body part and flags a body part that keeps recurring.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/pain-log` | Log a pain entry |
| GET | `/api/pain-log/{userId}` | Paged pain history |
| GET | `/api/pain-log/patterns/{userId}` | Recurring-pain alerts |

### `POST /api/pain-log`
Logs a pain entry for the caller, optionally linked to a workout.
- **Auth:** `Bearer`
- **Body:**
  ```json
  {
    "activityId": "9d4e…",
    "bodyPart": "LEFT_KNEE",
    "severity": 3,
    "description": "Sharp twinge on the downhill section"
  }
  ```
  - `bodyPart` is one of `LEFT_KNEE`, `RIGHT_KNEE`, `LEFT_SHOULDER`, `RIGHT_SHOULDER`, `LOWER_BACK`, `UPPER_BACK`, `LEFT_ANKLE`, `RIGHT_ANKLE`, `NECK`, `HIPS`, `OTHER`.
  - `severity` is required and must be 1 to 5. An omitted value (it arrives as `0`) or one outside the range is refused: `400` "Pain severity must be between 1 and 5."
  - `activityId` is optional, and must be one of the caller's own activities. Omitted, `null` or blank saves the entry unlinked.
  - Nothing is technically required.
  - A `userId` in the body is ignored.
- **Response:** `200`, the `PainLog` entity:
  ```json
  {
    "id": "b81f…",
    "bodyPart": "LEFT_KNEE",
    "severity": 3,
    "description": "Sharp twinge on the downhill section",
    "loggedAt": "2026-09-25T18:22:09.771"
  }
  ```
  - The linked activity is `@JsonIgnore`d, so it is never returned, not even as an id.
- **Errors:**
  - `400` "Activity not found: <activityId>"
  - `403` "You don't have access to this activity." The activity belongs to someone else.
  - `400` "Pain severity must be between 1 and 5."
  - `400` "Pick the body part that hurts." `bodyPart` is required.
  - An unknown `bodyPart` returns the generic unreadable-request `400`.

### `GET /api/pain-log/{userId}`
Returns the caller's pain history, newest first.
- **Auth:** `Bearer`
- **Params:** `userId` (path, must be the caller) · `page` (int, default `0`, negative treated as 0) · `size` (int, default `20`, clamped to 1–100)
- **Response:** `200`. Paged (see Pagination) `PainLog` entities, same shape as the `POST` response, sorted by `loggedAt` descending.

### `GET /api/pain-log/patterns/{userId}`
Returns one alert for each body part reported 3 or more times in the last 21 days.
- **Auth:** `Bearer`
- **Params:** `userId` (path, must be the caller)
- **Response:** `200`, an array of strings. It is empty when there is no pattern. Order is not guaranteed.
  ```json
  ["You've reported left knee discomfort 3 times in 3 weeks. Consider resting this area and consulting a professional."]
  ```
- **Errors:** none specific. Entries with no `bodyPart` (possible in data saved before it was required) are skipped.

---

## Workout plans
Browse the built-in multi-day plans, enroll in one, and step through it one day at a time. Four system plans are seeded at startup: 7-Day Beginner Cardio, 7-Day Strength Starter, 30-Day Fat Loss Challenge and 14-Day Flexibility Program.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/plans` | List all system plans |
| GET | `/api/plans/{planId}` | One plan with all of its days |
| POST | `/api/plans/{planId}/enroll` | Enroll the caller in a plan |
| GET | `/api/plans/active/{userId}` | The caller's active plan and today's workout |
| POST | `/api/plans/active/{userId}/complete-day` | Mark today's plan day done and advance |

### `GET /api/plans`
Lists every system plan (`createdByUserId` is `null`). Each plan includes its full `days` array.
- **Auth:** `Bearer`
- **Response:** `200`, an array of `WorkoutPlan`, shaped as in `GET /api/plans/{planId}`.

### `GET /api/plans/{planId}`
Returns one plan with all of its days.
- **Auth:** `Bearer`
- **Params:** `planId` (path, string UUID)
- **Response:** `200`
  ```json
  {
    "id": "1f0a…",
    "name": "7-Day Strength Starter",
    "description": "Introduction to weight training with adequate rest days.",
    "totalDays": 7,
    "difficulty": "BEGINNER",
    "goal": "MUSCLE_GAIN",
    "createdByUserId": null,
    "days": [
      { "id": "aa01…", "dayNumber": 1, "activityType": "WEIGHT_TRAINING", "durationMinutes": 45, "instructions": "Upper body: 3x10 push-ups, rows, shoulder press", "isRestDay": false },
      { "id": "aa02…", "dayNumber": 2, "activityType": null, "durationMinutes": 0, "instructions": "Rest", "isRestDay": true }
    ],
    "createdAt": "2026-09-01T09:00:00.120"
  }
  ```
  - `difficulty` and `goal` are free strings. The seeded values are `BEGINNER` / `INTERMEDIATE` and `WEIGHT_LOSS` / `MUSCLE_GAIN` / `ENDURANCE` / `FLEXIBILITY`.
  - `activityType` is an `ActivityType` value, or `null` on rest days.
  - `days` has no guaranteed order; sort on `dayNumber`.
- **Errors:** `400` `"Plan not found: <planId>"`

### `POST /api/plans/{planId}/enroll`
Enrolls the caller in a plan starting today, at day 1. Any active enrollment is deactivated first, and enrolling in the same plan again restarts it.
- **Auth:** `Bearer`
- **Params:** `planId` (path)
- **Body:** A JSON body is **required**, even though nothing in it is used. Send `{}`; a request with no body gets the generic unreadable-request `400`.
  ```json
  { "userId": "ignored" }
  ```
- **Response:** `200`, an enrollment map:
  ```json
  {
    "enrollmentId": "e5d2…",
    "planName": "7-Day Strength Starter",
    "planDifficulty": "BEGINNER",
    "planGoal": "MUSCLE_GAIN",
    "startDate": "2026-09-25",
    "currentDay": 1,
    "totalDays": 7,
    "completedDays": 0,
    "progressPercent": 0.0,
    "isCompleted": false,
    "todaysWorkout": {
      "dayNumber": 1,
      "activityType": "WEIGHT_TRAINING",
      "durationMinutes": 45,
      "instructions": "Upper body: 3x10 push-ups, rows, shoulder press",
      "isRestDay": false
    }
  }
  ```
  - `progressPercent` = `completedDays / totalDays × 100`, rounded to one decimal place.
  - `todaysWorkout` is omitted once the plan is finished.
- **Errors:** `400` `"Plan not found: <planId>"`

### `GET /api/plans/active/{userId}`
Returns the caller's active enrollment in the same shape as the enroll response.
- **Auth:** `Bearer`
- **Params:** `userId` (path, must be the caller)
- **Response:** `200`, the enrollment map. With no active plan, including after finishing one, it is still `200`:
  ```json
  { "message": "No active plan. Browse plans at GET /api/plans" }
  ```

### `POST /api/plans/active/{userId}/complete-day`
Marks the current plan day as done and advances to the next. This is allowed at most once per calendar day. Rest days count as days.
- **Auth:** `Bearer`
- **Params:** `userId` (path, must be the caller)
- **Body:** None.
- **Response:** `200`, the enrollment map after advancing. On the final day, the response has `isCompleted: true` and no `todaysWorkout`, and the enrollment becomes inactive.
- **Errors:**
  - `400` `"No active plan. Enroll in a plan first."`
  - `400` `"Today's workout is already marked complete. Come back tomorrow."`

---

## Recommendations
AI coaching advice built from the caller's last five activities. A recommendation is also generated automatically on every 3rd activity save; these endpoints trigger one manually and read them back.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/recommendation/generate` | Generate a recommendation for one of the caller's activities |
| GET | `/api/recommendation/user/{userId}` | All of the caller's recommendations |
| GET | `/api/recommendation/activity/{activityId}` | Recommendations linked to one activity |

### `POST /api/recommendation/generate`
Asks Gemini for a recommendation based on the caller's 5 most recent activities, linked to the given activity.
- **Auth:** `Bearer`
- **Rate limit:** AI tier.
- **Body:**
  ```json
  { "activityId": "9d4e…" }
  ```
  - `activityId` is required and must be one of the caller's activities.
  - A `userId` in the body is ignored.
- **Response:** `200`, the `Recommendation` entity:
  ```json
  {
    "id": "71aa…",
    "type": "RECOVERY",
    "recommendation": "You've stacked three high-intensity sessions back to back. Take an easy day before your next HIIT block.",
    "improvements": ["Add a mobility session after long runs", "Alternate hard and easy days"],
    "suggestions": ["Try a 20-minute zone-2 ride", "Stretch hamstrings post-run", "Keep one full rest day per week"],
    "safety": ["Stop if the knee pain returns during descents"],
    "createdAt": "2026-09-25T19:04:55.812",
    "updatedAt": "2026-09-25T19:04:55.812"
  }
  ```
  - `type` is whatever the model returns. The prompt asks for `PERFORMANCE`, `RECOVERY`, `SAFETY` or `MOTIVATION`, but this is not enforced.
  - If Gemini is unconfigured or the call fails, the endpoint **does not error**. It saves a fixed rule-based fallback with `type: "MOTIVATION"`, `recommendation: "Keep going! You're on a <streak>-day streak. …"`, two `improvements`, three `suggestions` and `safety: []`.
  - The linked user and activity are not serialized.
- **Errors:**
  - `400` `"Activity not found: <activityId>"`
  - `403` `"You don't have access to this activity."`
  - `400` "activityId is required: the recommendation is about one workout."

### `GET /api/recommendation/user/{userId}`
Lists every recommendation for the caller.
- **Auth:** `Bearer`
- **Params:** `userId` (path, must be the caller)
- **Response:** `200`, an array of `Recommendation` entities, same shape as the generate response. The list is unpaged and has no defined order.

### `GET /api/recommendation/activity/{activityId}`
Lists the recommendations linked to one of the caller's activities.
- **Auth:** `Bearer`
- **Params:** `activityId` (path, string UUID)
- **Response:** `200`, an array of `Recommendation` entities. It may be empty.
- **Errors:** `400` `"Activity not found: <activityId>"` · `403` `"You don't have access to this activity."`

---

## Friends
Connections between two users, made by email address rather than by pasting a raw user id. Each friendship has one row per pair, addressed by its own `friendshipId`. The service checks that the caller is one of the two people in it.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/friends` | List accepted friends |
| GET | `/api/friends/invites` | List requests waiting for the caller to answer |
| GET | `/api/friends/sent` | List requests the caller sent that nobody has answered yet |
| POST | `/api/friends/request` | Send a friend request by email |
| POST | `/api/friends/{friendshipId}/accept` | Accept a request sent to the caller |
| DELETE | `/api/friends/{friendshipId}` | Decline a request, cancel one, or unfriend |

**Friend row.** The three GET endpoints return a list of these, newest `createdAt` first. Each row describes the other person as the caller sees them.
```json
{
  "friendshipId": "5b0e4c1a-8f5e-4d7e-9a53-2f1b7c9d0e11",
  "userId": "a7c3e2d4-1b6f-4e8a-9c2d-3f4e5a6b7c8d",
  "name": "Ravi Iyer",
  "currentStreak": 4,
  "status": "ACCEPTED",
  "isIncoming": false,
  "createdAt": "2026-09-20T18:02:11",
  "respondedAt": "2026-09-20T19:15:40"
}
```
- `userId`, `name` and `currentStreak` describe the other person. `name` is their full name, or `"Athlete"` if they have none. `currentStreak` can be `null`.
- The row never includes the other person's email address.
- `status` is `PENDING` or `ACCEPTED`. There is no DECLINED state, because a declined request is deleted.
- `isIncoming` is `true` when the caller was the person asked. For accepted friendships it still records who asked first.
- `respondedAt` is `null` while the request is pending.

### `GET /api/friends`
Lists the caller's accepted friends.
- **Auth:** `Bearer`
- **Response:** `200`, an array of friend rows with `status: "ACCEPTED"`. The array is `[]` when there are none.

### `GET /api/friends/invites`
Lists pending requests where the caller is the person asked. This is the invite badge.
- **Auth:** `Bearer`
- **Response:** `200`, an array of friend rows with `status: "PENDING"`, `isIncoming: true` and `respondedAt: null`.

### `GET /api/friends/sent`
Lists pending requests the caller sent that nobody has answered yet.
- **Auth:** `Bearer`
- **Response:** `200`, an array of friend rows with `status: "PENDING"` and `isIncoming: false`.

### `POST /api/friends/request`
Sends a friend request to the account registered with an email address.
- **Auth:** `Bearer`
- **Rate limit:** invite tier (10/min, shared with the other invite route)
- **Body:**
  ```json
  { "email": "ravi@example.com" }
  ```
  `email` is required. It is trimmed and lowercased before the lookup.
- **Response:** `200`, `{"message": "..."}`. **The reply does not reveal whether the address is registered.** An unknown address gets exactly the same 200 and message as a known one:
  ```json
  { "message": "If that address belongs to a FitPilot account, a friend request is on its way." }
  ```
  The same message comes back when a request to that person is already pending.

  Two other messages are possible. Both only reach someone who already has a relationship with that account:
  - `"You're already friends with them."`
  - `"You're now friends with <Name>."`: the other person had already sent the caller a request. That request is accepted instead of creating a second one.

  A new request also creates a `GENERAL` notification for the person asked, with `targetPanel: "challenges"`.
- **Errors:**
  - `400` `"Enter the email address of the person you want to add."` when `email` is missing or blank.
  - `400` `"That's your own address."`

### `POST /api/friends/{friendshipId}/accept`
Accepts a pending request. Only the person who was asked can accept it.
- **Auth:** `Bearer`
- **Params:** `friendshipId` (path, string UUID)
- **Response:** `200`
  ```json
  { "message": "You're now friends with Asha Rao." }
  ```
  If the request was already accepted, the reply is `{"message": "You're already friends."}` and nothing changes. On success, the requester receives a `GENERAL` notification with `targetPanel: "challenges"`.
- **Errors:**
  - `400` `"Friend request not found: <friendshipId>"`
  - `403` `"Only the person who was asked can accept this request."` This includes the requester trying to accept their own request.

### `DELETE /api/friends/{friendshipId}`
Removes the friendship row. Depending on the state, this declines an incoming request, withdraws a sent one, or unfriends. Either of the two people may do it.
- **Auth:** `Bearer`
- **Params:** `friendshipId` (path, string UUID)
- **Response:** `204 No Content`. Nothing about the decline is kept, so the same pair can send a new request later.
- **Errors:**
  - `400` `"Friend request not found: <friendshipId>"`
  - `403` `"You don't have access to this friendship."`

---

## Challenges
Time-boxed competitions for 2 to 10 people. Membership and scores are stored per participant (`ChallengeParticipant`). Only participants whose status is `ACCEPTED` are scored and ranked. Scores are recomputed from the activity log over the challenge's `[startDate, endDate]` window, so someone who accepts late is scored over the same full window as everyone else.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/challenges` | Create a challenge and invite people |
| POST | `/api/challenges/{challengeId}/respond` | Accept or decline an invite |
| POST | `/api/challenges/{challengeId}/leave` | Leave a challenge you joined |
| DELETE | `/api/challenges/{challengeId}/cancel` | Creator cancels a challenge that is still pending |
| GET | `/api/challenges/user/{userId}` | All of the caller's challenges, in any status |
| GET | `/api/challenges/{challengeId}` | One challenge, from the caller's perspective |
| GET | `/api/challenges/invites/{userId}` | Invites the caller hasn't answered |
| GET | `/api/challenges/{challengeId}/leaderboard` | Daily standings snapshots (graph data) |
| GET | `/api/challenges/h2h/{userAId}/{userBId}` | All-time head-to-head record between the caller and one other user |

**Enums.**
- `ChallengeType` sets how a score is computed over the window:
  - `CALORIES_BURNED`: sum of kcal
  - `WORKOUT_COUNT`: number of activities
  - `TOTAL_DURATION`: sum of minutes
  - `STREAK_LENGTH`: longest run of consecutive active days inside the window
- `ChallengeStatus` values:
  - `PENDING`: created, and no invitee has accepted yet.
  - `ACTIVE`: at least one invitee accepted. The clock starts at the first acceptance.
  - `COMPLETED`: settled after `endDate`. The settlement job runs daily at 00:05 for challenges where `endDate < today`.
  - `DECLINED`: every invitee declined.
  - `CANCELLED`: either the creator cancelled it while it was `PENDING`, or it was settled with fewer than 2 competitors.
- `ParticipantStatus` values:
  - `INVITED`: has not answered. Not ranked.
  - `ACCEPTED`: competing. The creator is `ACCEPTED` from the moment the challenge is created.
  - `DECLINED`: declined the invite, or left. Not ranked, but still listed.

**Challenge object.** Every endpoint that returns a challenge uses this shape. It is always seen from the caller's perspective.
```json
{
  "id": "0f3c2b1a-7d6e-4c5b-8a9f-1e2d3c4b5a69",
  "title": "Asha + 2 others — CALORIES BURNED",
  "type": "CALORIES_BURNED",
  "status": "ACTIVE",
  "startDate": "2026-09-21",
  "endDate": "2026-09-28",
  "participants": [
    { "userId": "u-asha",  "name": "Asha Rao",  "score": 1840.0, "status": "ACCEPTED", "isCreator": true,  "isYou": true,  "position": 1 },
    { "userId": "u-ravi",  "name": "Ravi Iyer", "score": 1210.0, "status": "ACCEPTED", "isCreator": false, "isYou": false, "position": 2 },
    { "userId": "u-meera", "name": "Meera N",   "score": 0.0,    "status": "INVITED",  "isCreator": false, "isYou": false, "position": null }
  ],
  "participantCount": 2,
  "creator": { "id": "u-asha", "name": "Asha Rao" },
  "currentLeader": "Asha",
  "leadMargin": 630.0,
  "daysRemaining": 3,
  "yourScore": 1840.0,
  "yourStatus": "ACCEPTED",
  "youAreCreator": true,
  "yourPosition": 1
}
```

Participants and counts:
- `participants` lists everyone who was invited. `ACCEPTED` participants come first, ranked by score from highest, with `position` counting up from 1. `INVITED` and `DECLINED` participants follow with `position: null`.
- A participant who left keeps the last score that was stored for them.
- `participantCount` counts only `ACCEPTED` participants. It is not the number of people invited.

Fields that only appear when `status` is `ACTIVE`:
- `currentLeader`: the leader's first name, or `"Tied"` when the top two scores are level. It is also `"Tied"` when only one competitor is left.
- `leadMargin`: the top score minus the second score. It is `0.0` when tied or when only one competitor is left.
- `daysRemaining`: days from today to `endDate`, never below 0.

Fields that only appear when `status` is `COMPLETED`:
- `winner`: the winner's full name, or `"Draw"`.
- `resultMessage`: for example `"Asha wins! 1840.0 calories burned, ahead of Ravi on 1210.0, from 3 competitors."`.

A challenge settled as `CANCELLED` because nobody else joined also carries `resultMessage` (`"Nobody else joined, so there was no contest."`), and has no `winner`. One its creator cancelled while it was pending has neither.

`your*` fields describe the caller. They are always present, because every endpoint requires the caller to be a participant.
- `yourStatus`: the caller's `ParticipantStatus`.
- `youAreCreator`: whether the caller created the challenge.
- `yourPosition`: the caller's rank, or `null` unless they are `ACCEPTED`.

Score freshness:
- For `ACTIVE` challenges, `GET /user/{userId}` and `GET /{challengeId}` recompute scores live, so today's workouts are included.
- Every other endpoint returns the scores stored by the nightly 23:55 job.

### `POST /api/challenges`
Creates a challenge and invites one or more people. The caller is the creator and is already `ACCEPTED`.
- **Auth:** `Bearer`
- **Rate limit:** invite tier (10/min, shared with the other invite route)
- **Body:**
  ```json
  {
    "opponentIds": ["u-ravi", "u-meera"],
    "opponentId": "u-ravi",
    "type": "CALORIES_BURNED",
    "title": "September burn-off",
    "durationDays": 7,
    "creatorId": "ignored"
  }
  ```
  - `type` (required): one of the `ChallengeType` values.
  - `opponentIds` and `opponentId`: at least one invitee is required across the two fields. `opponentId` is the older single-opponent field and is still honoured. The server merges both fields, trims each id, drops blanks and duplicates, and removes the caller's own id. The caller plus the invitees can be at most 10 people, so up to 9 invitees.
  - **Every invitee must be an accepted friend** of the caller. Friends are made through `/api/friends`.
  - `title` (optional): if blank, the server generates one. For one invitee it is `"<First> vs <First> — <TYPE WITH SPACES>"`. For more it is `"<First> + N others — <TYPE WITH SPACES>"`.
  - `durationDays` (optional): defaults to `7` when it is omitted, `0` or negative. There is no upper limit.
  - `creatorId` is ignored. The creator is always the caller.
- **Response:** `200`, a challenge object with `status: "PENDING"`.
  - `startDate` and `endDate` are placeholders (today, and today + `durationDays`). They are reset when the first invitee accepts.
  - Each invitee receives a `GENERAL` notification with `targetPanel: "challenges"`.
- **Errors:** all `400`.
  - `"A challenge needs a type."`
  - `"You cannot challenge yourself."`: the only invitee listed was the caller.
  - `"Invite at least one other person to the challenge."`
  - `"A challenge can hold at most 10 people."`
  - `"You can only challenge your friends."`: an invitee is not a friend. An id that isn't an account gets this same message, so the endpoint can't be used to test which ids exist. One non-friend refuses the whole invite.
  - `"You already have an active or pending challenge with one of these people."`: this applies to a `PENDING` or `ACTIVE` challenge in which both the caller and that invitee are still in, meaning invited-and-unanswered or accepted. Someone who declined or left it is out of it and doesn't block a new challenge.
  - An unknown `type` value gets the standard malformed-request 400.

### `POST /api/challenges/{challengeId}/respond`
Accepts or declines an invite.
- **Auth:** `Bearer`
- **Params:** `challengeId` (path, string UUID)
- **Body:**
  ```json
  { "accept": true, "userId": "ignored" }
  ```
  - `accept` is a required boolean. If it is omitted or `null`, the request is refused with `400` and the invite is left unanswered. It used to default to `false`, which declined the invite.
  - `userId` is ignored. The person responding is always the caller.
- **Response:** `200`, the challenge object. What changes:
  - The first acceptance on a `PENDING` challenge makes it `ACTIVE`. It sets `startDate` to today and `endDate` to today plus the original duration.
  - Accepting a challenge that is already `ACTIVE` joins the window that is already running.
  - When the last invitee still deciding declines and nobody besides the creator has accepted, the challenge becomes `DECLINED`.
- **Errors:**
  - `400` `"Say whether to accept: send \"accept\" as true or false."` The answer is checked before anything about the challenge, so it applies even to an id that doesn't exist.
  - `400` `"Challenge not found: <id>"`
  - `403` `"You weren't invited to this challenge."`
  - `400` `"You created this challenge — you're already in it."`
  - `400` `"You've already responded to this challenge."`
  - `400` `"This challenge is over."`: the challenge is `COMPLETED` or `CANCELLED`.

### `POST /api/challenges/{challengeId}/leave`
Leaves a challenge the caller joined, without ending it for the others.
- **Auth:** `Bearer`
- **Params:** `challengeId` (path, string UUID)
- **Response:** `200`, the challenge object with `yourStatus: "DECLINED"` and `yourPosition: null`.
  - The caller's participant row becomes `DECLINED`, and they drop out of the ranking.
  - If only the creator is left, the challenge stays `ACTIVE` until `endDate`. It is then settled as `CANCELLED`.
- **Errors:**
  - `400` `"Challenge not found: <id>"`
  - `403` `"You're not part of this challenge."`
  - `400` `"The creator can't leave — cancel it instead."`
  - `400` `"This challenge is already over."`: the challenge is `COMPLETED`.
  - `400` `"You're no longer part of this challenge."`: the caller is already `DECLINED`.
  - `400` `"You haven't joined this challenge — decline the invite instead."`: the caller is still `INVITED`.

### `DELETE /api/challenges/{challengeId}/cancel`
Lets the creator cancel a challenge that is still `PENDING`.
- **Auth:** `Bearer`
- **Params:**
  - `challengeId` (path, string UUID)
  - `userId` (query, optional): **ignored completely**. It isn't even checked against the caller. The server always acts as the caller.
- **Response:** `200` (not 204)
  ```json
  { "message": "Challenge cancelled." }
  ```
- **Errors:**
  - `400` `"Challenge not found: <id>"`
  - `403` `"Only the creator can cancel a challenge."`: this also applies to non-participants.
  - `400` `"Only pending challenges can be cancelled."`

### `GET /api/challenges/user/{userId}`
Lists every challenge the caller has a participant row in, in any status and any participant status.
- **Auth:** `Bearer`
- **Params:** `userId` (path): must be the caller's own id.
- **Response:** `200`, an array of challenge objects, or `[]` when there are none.
  - The order is not guaranteed, because the query has no `ORDER BY`.
  - Scores of `ACTIVE` challenges are recomputed live.

### `GET /api/challenges/{challengeId}`
Returns one challenge from the caller's perspective.
- **Auth:** `Bearer`
- **Params:**
  - `challengeId` (path, string UUID)
  - `viewingUserId` (query, optional): **ignored**. The perspective is always the caller's.
- **Response:** `200`, the challenge object. Scores are recomputed live if the challenge is `ACTIVE`. Any participant can read it, including one who declined or left.
- **Errors:**
  - `400` `"Challenge not found: <id>"`
  - `403` `"You're not part of this challenge."`

### `GET /api/challenges/invites/{userId}`
Lists challenges where the caller is still `INVITED` and the challenge is `PENDING` or `ACTIVE`.
- **Auth:** `Bearer`
- **Params:** `userId` (path): must be the caller's own id.
- **Response:** `200`, an array of challenge objects with `yourStatus: "INVITED"` and `yourPosition: null`. These return the stored scores, not live ones.

### `GET /api/challenges/{challengeId}/leaderboard`
Returns the daily standings snapshots, oldest first. Use them as score-over-time graph data.
- **Auth:** `Bearer`
- **Params:** `challengeId` (path, string UUID)
- **Response:** `200`
  ```json
  [
    {
      "date": "2026-09-22",
      "standings": [
        { "userId": "u-asha", "name": "Asha Rao",  "score": 620.0 },
        { "userId": "u-ravi", "name": "Ravi Iyer", "score": 410.0 }
      ],
      "leadingUserId": "u-asha",
      "leadMargin": 210.0
    }
  ]
  ```
  - The nightly 23:55 job writes one snapshot per day for each `ACTIVE` challenge. The response is `[]` until the first snapshot exists.
  - `standings` lists only `ACCEPTED` competitors, highest score first.
  - With a single competitor, `leadMargin` is that competitor's own score. The live challenge object reports `0.0` in the same case.
  - **Snapshots written before migration V8 have `standings: null`.** Their `leadingUserId` and `leadMargin` are still populated.
- **Errors:**
  - `400` `"Challenge not found: <id>"`
  - `403` `"You're not part of this challenge."`

### `GET /api/challenges/h2h/{userAId}/{userBId}`
Returns the caller's all-time record against one other user. That user must be a friend, or someone the caller has competed against.
- **Auth:** `Bearer`
- **Params:**
  - `userAId` (path): must be the caller's own id.
  - `userBId` (path): a friend, or anyone the caller has finished a challenge with while both were `ACCEPTED`. Past opponents stay visible after an unfriend.
- **Response:** `200`
  ```json
  {
    "totalChallenges": 5,
    "userA": "Asha Rao",
    "userB": "Ravi Iyer",
    "userAWins": 2,
    "userBWins": 1,
    "draws": 1,
    "otherWinners": 1,
    "dominantPlayer": "Asha"
  }
  ```
  - Only `COMPLETED` challenges where both users ended as `ACCEPTED` are counted. A challenge either of them left is not counted.
  - `otherWinners` counts group challenges that a third person won.
  - `dominantPlayer` is the first name of whoever has more wins, or `"Even"`.
  - `userA` and `userB` are full names.
- **Errors:**
  - `403` `"Head-to-head is only available with friends and people you've competed against."` An id that isn't an account gets this same 403, and no name is revealed.
  - `400` `"Head-to-head needs another person."`: `userBId` is the caller.

---

## Notifications
The caller's in-app feed of things the app worked out on its own schedule: digests, plateaus, challenge results, goal deadlines, and friend and challenge invites. Every route acts on the JWT's user. None of them has a userId segment.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/notifications` | The feed, newest first |
| GET | `/api/notifications/unread-count` | Unread count for the badge |
| POST | `/api/notifications/{notificationId}/read` | Mark one notification as read |
| POST | `/api/notifications/read-all` | Mark every notification as read |

**Notification object.**
```json
{
  "id": "9e8d7c6b-5a49-4382-9170-6f5e4d3c2b1a",
  "type": "CHALLENGE_RESULT",
  "title": "You won your challenge",
  "body": "Asha wins! Asha scored 1840.0 vs Ravi's 1210.0 calories burned.",
  "targetPanel": "challenges",
  "isRead": false,
  "readAt": null,
  "createdAt": "2026-09-29T00:05:02"
}
```
- `body` is at most 1000 characters.
- `isRead` is `true` exactly when `readAt` is set.
- Read notifications older than 60 days are deleted daily at 03:45.
- **`targetPanel` is the name of a frontend panel, not a URL.** The frontend routes on `#<panel>`.

**`NotificationType` values:**

| `type` | Sent when | `targetPanel` |
|---|---|---|
| `WEEKLY_DIGEST` | The scheduled Monday 08:00 digest is written | `ai` |
| `PLATEAU` | The plateau scan finds an activity type that has stopped improving | `insights` |
| `CHALLENGE_RESULT` | A challenge is settled. Sent to every `ACCEPTED` competitor. A challenge settled with no contest is titled "Your challenge was cancelled", not "ended in a draw" | `challenges` |
| `GOAL_DEADLINE` | A goal that isn't met yet is due within 7 days. Sent once per goal and deadline | `goals` |
| `PERSONAL_BEST` | Defined, but no code sends it yet | — |
| `GENERAL` | A friend request is received or accepted, or a challenge invite arrives | `challenges` |

### `GET /api/notifications`
Returns the caller's feed, newest `createdAt` first. Read and unread notifications are mixed together.
- **Auth:** `Bearer`
- **Params:**
  - `page` (query, int, default `0`): a negative value is treated as `0`.
  - `size` (query, int, default `20`): clamped to the range 1–100.
- **Response:** `200`, paged (see Pagination). `content` holds notification objects.

### `GET /api/notifications/unread-count`
Returns only the number of unread notifications. This is the endpoint the badge polls.
- **Auth:** `Bearer`
- **Response:** `200`
  ```json
  { "unread": 3 }
  ```

### `POST /api/notifications/{notificationId}/read`
Marks one notification as read. Calling it again is safe: if the notification is already read, `readAt` does not change.
- **Auth:** `Bearer`
- **Params:** `notificationId` (path, string UUID)
- **Response:** `200`, the updated notification object with `isRead: true`.
- **Errors:**
  - `400` `"Notification not found: <id>"`
  - `403` `"You don't have access to this notification."`

### `POST /api/notifications/read-all`
Marks every unread notification as read in a single statement.
- **Auth:** `Bearer`
- **Response:** `200`, where `marked` is the number of notifications that changed (`0` if none were unread).
  ```json
  { "marked": 5 }
  ```

---

## Weekly digest
A summary of one Monday-to-Sunday week that looks at training, strength, nutrition, sleep, body weight, pain and goals together. The server computes the numbers from the user's own records. Gemini only writes the prose around them. Without a Gemini key, or when the call fails, a rule-based fallback writes the prose instead.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/digest` | The most recent digest |
| GET | `/api/digest/history` | Every digest, newest week first |
| POST | `/api/digest/generate` | Build or rebuild the digest for a week |

**Digest object.**
```json
{
  "id": "3d2c1b0a-9f8e-4d7c-8b6a-5f4e3d2c1b0a",
  "weekStart": "2026-09-14",
  "weekEnd": "2026-09-20",
  "headline": "Volume up, bench flat — and sleep may be why.",
  "narrative": "Training time rose 18% on the week before, but your bench press didn't move. You averaged 5.8h across 5 logged nights, which is a plausible explanation.",
  "observations": ["Training time was up 18.0% on the week before.", "Flat or down since last session: Bench Press."],
  "focusNextWeek": ["Prioritise 7+ hours of sleep before adding volume."],
  "dataPoints": {
    "weekStart": "2026-09-14",
    "weekEnd": "2026-09-20",
    "training": {
      "sessions": 4, "daysTrained": 4, "minutes": 212, "calories": 1840, "volumeKg": 12450.0,
      "byType": { "WEIGHT_TRAINING": 3, "RUNNING": 1 },
      "sessionsPreviousWeek": 3, "minutesPreviousWeek": 180, "minutesChangePercent": 17.8
    },
    "strength": { "movementsTrained": 5, "improving": ["Squat +2.4%"], "stalled": ["Bench Press"] },
    "nutrition": {
      "daysLogged": 5, "mealsLogged": 14, "avgCaloriesPerLoggedDay": 2310, "avgProteinPerLoggedDay": 132.4,
      "maintenanceCalories": 2450, "avgNetPerLoggedDay": -508
    },
    "sleep": { "nightsLogged": 5, "avgHours": 5.8, "avgQuality": 3.2 },
    "bodyWeight": { "weightAtWeekEnd": 78.4, "changeKg": -0.6 },
    "pain": { "entries": 1, "maxSeverity": 2, "bodyParts": ["LEFT_SHOULDER"] },
    "goals": [ { "type": "WORKOUTS_PER_WEEK", "target": 4.0, "current": 4.0, "unit": "workouts", "completed": true } ],
    "currentStreak": 6,
    "hasAnythingLogged": true
  },
  "aiGenerated": true,
  "createdAt": "2026-09-21T08:00:04"
}
```
- `weekStart` is always a Monday, and `weekEnd` is `weekStart + 6`.
- `aiGenerated: false` means the rule-based fallback wrote `headline`, `narrative`, `observations` and `focusNextWeek`. The fallback states the numbers without interpreting them, and its `narrative` says so.
- **`dataPoints` holds the computed facts the prose was written from.** It is stored with the digest, so the text can be checked against it later. The model never supplies these numbers. Its structure is dynamic, and sub-keys appear only when they have data:
  - `nutrition` is just `{"daysLogged": 0}` when no meals were logged. `maintenanceCalories` and `avgNetPerLoggedDay` are present only if the profile lets maintenance calories be calculated.
  - `avgNetPerLoggedDay` is the average intake on the days with food logged, minus maintenance, minus the average training burn **on those same days**. A workout on a day with no food logged is left out of it (it still counts in `training`).
  - `sleep.avgHours` and `sleep.avgQuality` are present only when at least one night was logged.
  - `bodyWeight` is `{}` when no weight was logged during the week.
  - `pain.maxSeverity` and `pain.bodyParts` are present only when there is at least one pain entry.
  - `training.minutesChangePercent` is `null` when the previous week had no minutes.
  - `strength.improving` entries have the form `"<Exercise> +<pct>%"`. `strength.stalled` lists exercises whose estimated 1RM was flat or went down since the previous session.

### `GET /api/digest`
Returns the digest with the latest `weekStart`.
- **Auth:** `Bearer`
- **Response:** `200` with a digest object, or **`204 No Content`** with an empty body if no digest has been generated yet.

### `GET /api/digest/history`
Returns every digest for the caller, newest `weekStart` first.
- **Auth:** `Bearer`
- **Response:** `200`, an array of digest objects, or `[]` when there are none.

### `POST /api/digest/generate`
Builds the digest for one week, or rebuilds it if it already exists.
- **Auth:** `Bearer`
- **Rate limit:** AI tier.
- **Params:** `weekStart` (query, ISO date `YYYY-MM-DD`, optional)
  - Any date is moved back to the Monday of its week.
  - Defaults to the last completed Monday-to-Sunday week, meaning the Monday of the date one week ago.
  - The current week, still in progress, is allowed.
- **Response:** `200`, the digest object.
  - There is one digest per user per week. Regenerating a week overwrites the same row, and `id` stays the same.
  - An on-demand run creates no notification or email. Only the scheduled Monday 08:00 run does that.
  - A failed Gemini call is not an error. The request still succeeds, with `aiGenerated: false`.
- **Errors:**
  - `400` `"Nothing was logged in the week of <yyyy-MM-dd>, so there is nothing to summarise yet."`: the week has no activities, meals, sleep or pain entries. Body-weight entries alone don't count.
  - A malformed `weekStart` gets the standard malformed-request 400.

---

## Export
Downloads the caller's whole account, either as a single JSON document or as CSV files. Every route acts on the JWT's user only. None of them takes a userId in the path or the query.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/export/datasets` | List the datasets and formats you can export |
| GET | `/api/export/json` | The whole account as one JSON download |
| GET | `/api/export/csv/{dataset}` | One dataset as a CSV download |
| GET | `/api/export/zip` | Every dataset as CSV files inside one zip |

All four routes are on the standard rate-limit tier.

**Datasets and their columns.** CSV headers and JSON row keys use the same names in the same order.

| Dataset | Columns |
|---|---|
| `activities` | id, type, startTime, completedAt, durationMinutes, caloriesBurned, distanceKm, totalVolumeKg, difficultyRating, notes, createdAt |
| `exercises` | id, activityId, activityStartTime, name, sets, reps, weightKg, volumeKg, estimatedOneRepMax, orderIndex |
| `meals` | id, loggedAt, mealType, description, calories, proteinGrams, carbsGrams, fatGrams, aiEstimated, createdAt |
| `goals` | id, type, period, direction, unit, targetValue, currentValue, startValue, periodStart, deadline, isCompleted, createdAt |
| `body-metrics` | loggedAt, weightKg, heightCm, bmi, bmiCategory |
| `sleep` | id, sleepDate, hoursSlept, qualityRating, bedtime, wakeTime, notes, loggedAt |
| `water` | id, logDate, amountMl, loggedAt |
| `pain` | id, loggedAt, bodyPart, severity, description |
| `personal-bests` | activityType, bestDuration, bestCalories, bestDistanceKm, achievedAt |
| `exercise-records` | exercise, bestWeightKg, bestWeightReps, bestEstimatedOneRepMax, bestVolume, bestBodyweightReps, achievedAt |

**Password hashes and roles are never included in any export.**

### `GET /api/export/datasets`
Lists the dataset names and formats, so a client doesn't need to hard-code them.
- **Auth:** `Bearer`
- **Response:** `200`. The order of the two top-level keys is not fixed.
  ```json
  {
    "datasets": ["activities", "exercises", "meals", "goals", "body-metrics", "sleep", "water", "pain", "personal-bests", "exercise-records"],
    "formats": ["json", "csv", "zip"]
  }
  ```

### `GET /api/export/json`
Returns the whole account as a single JSON document.
- **Auth:** `Bearer`
- **Response:** `200`
  - `Content-Type: application/json`
  - `Content-Disposition: attachment; filename="fitpilot-export-<yyyy-MM-dd>.json"`, where the date is the server's current date.
  ```json
  {
    "exportedAt": "2026-09-25T14:03:27.481",
    "schemaVersion": 1,
    "profile": {
      "id": "u-asha", "email": "asha@example.com", "firstName": "Asha", "lastName": "Rao",
      "weightKg": 62.0, "heightCm": 165.0, "age": 29,
      "currentStreak": 6, "longestStreak": 21, "memberSince": "2025-11-02T10:14:55"
    },
    "data": {
      "activities": [ { "id": "…", "type": "RUNNING", "startTime": "2026-09-20T07:30:00", "durationMinutes": 35, "caloriesBurned": 360, "distanceKm": 5.2, "totalVolumeKg": null, "difficultyRating": 3, "notes": "Easy pace", "createdAt": "2026-09-20T08:10:12" } ],
      "exercises": [], "meals": [], "goals": [], "body-metrics": [], "sleep": [],
      "water": [], "pain": [], "personal-bests": [], "exercise-records": []
    }
  }
  ```
  - `data` always contains all ten datasets. An empty dataset is `[]`.
  - Enum values are written as their names, and dates as ISO strings.
  - `profile` never includes the password hash or the role.
- **Errors:** `409`, the account-gone 409 (see Conventions).

### `GET /api/export/csv/{dataset}`
Downloads one dataset as a CSV file. The file is streamed.
- **Auth:** `Bearer`
- **Params:** `dataset` (path): one of the dataset names above.
- **Response:** `200`
  - `Content-Type: text/csv;charset=UTF-8`
  - `Content-Disposition: attachment; filename="fitpilot-<dataset>-<yyyy-MM-dd>.csv"`
  - The file starts with a UTF-8 BOM, so Excel reads it as UTF-8. Lines end with CRLF.
  - The header row is always present, even when the dataset is empty.
  - Every non-null cell is double-quoted, with any `"` inside doubled. A null value is an empty cell.
  - **Formula-injection neutralising:** a text cell that starts with `=`, `+`, `-`, `@`, a tab or a carriage return gets a `'` in front, so spreadsheet apps treat it as text. Numeric columns are never prefixed, so a negative number such as `-0.6` stays a number.
  - Date and time values use Java's `toString()` form, for example `2026-09-20T07:30`.
  ```csv
  id,loggedAt,bodyPart,severity,description
  "7a1b…","2026-09-18T19:02:44","LEFT_SHOULDER","2","'=HYPERLINK(""http://x"",""click"")"
  ```
- **Errors:** both checks run before any bytes are written, the account check first.
  - `409`: the account-gone 409 (see Conventions).
  - `400` `"Unknown dataset: <name>. Available: activities, exercises, meals, goals, body-metrics, sleep, water, pain, personal-bests, exercise-records"`.

### `GET /api/export/zip`
Downloads every dataset as CSV files packed into one zip, streamed.
- **Auth:** `Bearer`
- **Response:** `200`
  - `Content-Type: application/zip`
  - `Content-Disposition: attachment; filename="fitpilot-export-<yyyy-MM-dd>.zip"`
  - The zip holds one entry per dataset, named `fitpilot-<dataset>.csv` (for example `fitpilot-body-metrics.csv`).
  - Each entry uses the same CSV format as `/csv/{dataset}`, except that zip entries have no BOM.
- **Errors:** `409`, the account-gone 409 (see Conventions). It is checked before any bytes are written.
