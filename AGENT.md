# AGENT.md — Coding Conventions & Hard-Won Gotchas

Read this before writing or editing any code in this repo. Every rule here exists because skipping it broke something in a way that took real debugging effort to trace back. This file is the distilled memory of that debugging.

---

## 1. Backend structure & conventions

- **Layering is strict:** `Controller → Service → Repository`. Controllers never touch repositories directly, and never contain business logic — they parse the request, call one service method, return the result.
- **Lombok everywhere:** `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor` on entities and DTOs. `@RequiredArgsConstructor` on services/controllers for constructor injection — never field injection (`@Autowired` on a field), never mix `@Autowired` and `@RequiredArgsConstructor` on the same class (caused a duplicate-injection bug once).
- **`@Builder.Default` is not optional.** If an entity field has an inline initializer (`private Integer currentStreak = 0;`) and the class also has `@Builder`, Lombok silently drops that initializer unless you also add `@Builder.Default`. Without it, building via `User.builder().build()` gives you `null`, not `0` — a silent bug, not a compile error. Check every field with an inline default against this rule.
- **DTOs never mirror entities 1:1 on purpose.** `UserResponse` deliberately omits `password`. Any time you add a field to an entity, ask whether it belongs in the response DTO — don't assume yes.

## 2. Identity and authorization (read this twice)

**Identity comes from the JWT. Never from a path variable, a query parameter, or a request body.** A client controls all three.

This was documented as a rule long before it was actually true. For most of the project's life exactly one endpoint followed it (`ActivityController.getUserActivities`), while every other controller took a `userId` straight from the URL or body and acted on it without a single check — so any logged-in user could read or rewrite anyone else's profile, goals, stats, health logs, pain logs, plans, recommendations and challenges by changing a UUID. `POST /api/activities` wrote to whatever `userId` the body named. `rateActivity`, `addNotes`, `updateGoal` and `deleteGoal` took only a resource id and never checked who owned it.

**The mechanism:** `security/CurrentUser` is the only way to answer "who is calling".

```java
CurrentUser.id()                      // the authenticated user id; 403 if unauthenticated
CurrentUser.idOrNull()                // same, but null instead of throwing
CurrentUser.requireSelf(pathUserId)   // asserts pathUserId == caller, returns the caller id
CurrentUser.requireOwner(ownerId, "goal")   // asserts the caller owns a resource
```

**The three patterns, and when each applies:**

1. **URL still carries `{userId}`** (profile, stats, health, pain, recovery, bests, goals, plans, challenges, recommendations) — wrap it: `profileService.getProfile(CurrentUser.requireSelf(userId))`. The segment is kept so the frontend and `API.md` don't have to change; it's verified rather than trusted.
2. **`userId` in a request body** (activities, goals, pain logs, plan enrolment, NL logging, challenge creation) — ignore it and use `CurrentUser.id()`. The DTO field stays for backwards compatibility with a comment saying it's ignored.
3. **Resource addressed by id alone** (an activity, a goal, a challenge) — the *service* loads it and compares the owner against the caller id the controller passed in, throwing `AccessDeniedException`. Keep this check in the service, not the controller: the controller doesn't have the entity, and the check has to happen inside the transaction that can read the lazy `user` relationship.

```java
// Service signature takes the caller's id explicitly — keeps the service unit-testable
// without a SecurityContext, and makes the check impossible to forget at the call site.
@Transactional
public ActivityResponse rateActivity(String requestingUserId, String activityId, int rating) {
    Activity activity = activityRepository.findById(activityId).orElseThrow(...);
    requireOwner(activity, requestingUserId);   // throws AccessDeniedException
    ...
}
```

**Roles are assigned server-side, never accepted from a request.** `RegisterRequest` deliberately has **no `role` field**. It used to, and `UserService.register` wrote it straight onto the saved `User` — so `POST /api/auth/register {"role":"ADMIN"}` self-provisioned an admin on a public endpoint. Every registration creates a plain `USER`.

**`/api/auth/logout` must prove identity.** It sits under the permitAll `/api/auth/**` prefix and used to accept a bare `userId`, which meant anyone could revoke anyone else's sessions, repeatedly, with no credentials. It now resolves the user from the access token, or from a live refresh token (`resolveUserId` ignores one that is revoked or expired); a `userId` in the body is ignored. It always returns 200 — a failed logout must never leave someone stuck logged in.

**When adding an endpoint, ask: can the caller name someone other than themselves?** If yes, it needs one of the three patterns above. There is an integration-test block (`ActivityControllerIntegrationTest`, "Cross-user authorization") that registers a second account and asserts it gets 403 — add to it.

## 3. The `@Transactional` / lazy-loading rule (read this twice too)

Every JPA relationship in this codebase (`@ManyToOne`, `@OneToMany`) is `FetchType.LAZY`. That means: the moment a service method returns and the Hibernate session closes, calling `.getUser()` / `.getActivity()` / `.getCreator()` etc. on an entity **throws `LazyInitializationException`** (this has happened with `rateActivity`, `addNotes`, every `ChallengeService` read method, and `RecommendationService`'s list endpoints — all fixed, but the pattern will bite again if not respected).

**The rule:** any service method that reads a lazy relationship — directly, or indirectly through a mapper/response-builder — must be annotated `@Transactional` (writes) or `@Transactional(readOnly = true)` (reads). No exceptions, no "it's just a getter."

```java
// WRONG — will throw LazyInitializationException the moment mapToResponse touches activity.getUser()
public ActivityResponse rateActivity(String activityId, int rating) {
    Activity activity = activityRepository.findById(activityId).orElseThrow(...);
    activity.setDifficultyRating(rating);
    activityRepository.save(activity);
    return mapToResponse(activity, activity.getUser(), ...); // <- boom, session already closed
}

// RIGHT
@Transactional
public ActivityResponse rateActivity(String requestingUserId, String activityId, int rating) {
    ...same body, plus the ownership check from §2...
}
```

Note that **the ownership checks in §2 read `entity.getUser().getId()`** — which is a lazy relationship. Every method doing one must therefore be transactional. `RecommendationService.generateRecommendation` was made `@Transactional` for exactly this reason: it is called straight from a controller, and the caller's ownership check touches `activity.getUser()`.

When adding a new service method, ask: *does this method, or anything it calls, read a `@ManyToOne`/`@OneToMany` field?* If yes, it needs `@Transactional`.

## 4. Security, CORS, and status codes

- All CORS config lives in one place: `SecurityConfig.corsConfigurationSource()`. It allows the origin of whatever `FRONTEND_URL` env var is set to (a path is dropped, since a browser's `Origin` never has one), plus `localhost:*`, `127.0.0.1:*` and `null` (file:// origin) everywhere except the `prod` profile (`app.cors.allow-local-origins=false`). Don't scatter `@CrossOrigin` annotations on individual controllers — it'll conflict with this and cause confusing partial-CORS failures.
- **`OPTIONS` preflight requests must never require auth or hit the rate limiter.** Both `SecurityConfig` (`.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()`) and `RateLimitingFilter` (early-return on `OPTIONS`) have this guard. If a future filter is added to the chain, it needs the same guard, or every cross-origin POST/PUT/DELETE from the browser will silently fail preflight.
- `server.port=${PORT:8080}` must stay dynamic — Render/Railway/Heroku-style platforms inject their own `PORT` env var at runtime. Hardcoding `8080` breaks deployment silently (app starts, binds to the wrong port, platform's health check never finds it).

### 401 vs 403 is load-bearing — do not blur it

- **401** = no token, expired token, invalid token. The frontend's `api()` wrapper silently refreshes and retries on 401, and only on 401.
- **403** = valid token, but not your data. Refreshing will not help; the frontend surfaces the message.

Spring's default for a stateless API returns **403 for both**. That meant a person whose 48h access token expired never triggered the refresh path and just saw errors on every panel. `SecurityConfig` now sets an explicit `authenticationEntryPoint` (401) and `accessDeniedHandler` (403). If you touch the filter chain, keep both.

`AccessDeniedException` thrown *inside* a controller or service never reaches Spring Security's `ExceptionTranslationFilter` — that only sees exceptions escaping the filter chain — so `GlobalExceptionHandler` has an explicit handler for it. Without that specific handler it falls into the `RuntimeException` catch-all and is reported as a 400, which reads like the client sent something malformed.

### Access tokens carry a version — pass the user's current one

`JwtAuthenticationFilter` compares the token's `tv` claim with the account's `tokenVersion` on every request and refuses a mismatch (a 401). A password change or reset calls `user.retireIssuedTokens()`, and logout calls `RefreshTokenService.endAllSessions()` (an atomic `UPDATE`, since logout holds only an id). That is how every token issued before them dies at once.

- **Mint a real token with `generateToken(id, role, user.getTokenVersion())`**, and only after any bump, so the caller's own fresh token carries the new version. The two-argument overload issues version 0 and exists for tests: use it in production code and that token dies the first time the password changes, with nothing to say why.
- A token with no `tv` claim counts as version 0, which is what keeps every pre-upgrade token working.
- An account that no longer exists is deliberately **not** refused in the filter. The request goes on and the controller returns the 409 the frontend recovers from; a 401 would just loop through the refresh.
- Logout bumps it too, so logging out on one device ends the access token on every device, matching the refresh tokens, which it already revoked everywhere. Don't switch logout back to `revokeAll()` alone: that left each device's access token alive for 48 hours. Because logout ends this much, a refresh token offered as proof must be live: `resolveUserId` used to accept a revoked or expired one, which let whoever still held a spent token sign the account out at will until the daily purge deleted it. Keep that filter.
- It costs one small query per authenticated request. If that ever matters, a short cache is the lever, at the price of the old token surviving for that long.

### Roles need the `ROLE_` prefix

`SecurityConfig` guards `/api/admin/**` with `.hasRole("ADMIN")`, and `hasRole()` looks for the authority **`ROLE_ADMIN`**. `JwtAuthenticationFilter` was granting a bare `ADMIN`, so that rule could never match anyone — a dead rule that looked live. The filter now adds the prefix. `JwtAuthenticationFilterTest` asserts it.

### Rate limiting

`RateLimitingFilter` (Bucket4j) applies **four tiers**, all tunable via `app.rate-limit.*`:

| Tier | Per minute | Keyed by | Paths |
|---|---|---|---|
| `AUTH` | 10 | **client IP, always** | `/api/auth/**` |
| `AI` | 10 | JWT subject, else IP | `/api/activities/natural`, `/api/nutrition/meals/natural`, `/api/recommendation/generate`, `/api/digest/generate` |
| `INVITE` | 10 | JWT subject, else IP | `POST /api/friends/request`, `POST /api/challenges` |
| `STANDARD` | 100 | JWT subject, else IP | **every other `/api/**` route** |

Things here that are load-bearing, in the order they were learned:

- **The client IP is `request.getRemoteAddr()`, never the raw `X-Forwarded-For` header.** The filter used to take the header's first entry, which the client writes, so a fresh value per request got a fresh auth bucket: unlimited password guessing (25 in a row went through against the live app). Behind Render's proxy, `server.forward-headers-strategy=native` has Tomcat's RemoteIpValve fill `getRemoteAddr()` from the header, trusting only entries added by an internal proxy. Don't read the header in the filter again; if Render's proxy ever connects from outside Tomcat's private-range defaults, set `server.tomcat.remoteip.internal-proxies` instead. Verify on each new deployment that two clients get separate buckets.
- **An over-full bucket map is cut back to 90%.** It used to be cut only to the ceiling, so it stayed full and every later request re-ran the whole sweep.
- **Buckets key on the JWT subject, not the token.** Tokens rotate on every refresh, so keying on the token string handed each caller a brand-new full bucket (the limit was trivially resettable) and orphaned the old entry forever.
- **The auth tier ignores the token entirely and keys on IP.** Otherwise anyone holding one valid account could reset the login bucket at will, which is exactly the caller a login limit exists to stop.
- **The lookup fails closed.** It was an allowlist, so every route nobody remembered to list (friends, challenges, notifications, goals, health and more) had no limit at all (PLAN P4-5). Now anything under `/api/` is at least `STANDARD`, and a new route is covered without touching this filter. Only add a line to `tierFor()` to put a route in a *stricter* tier.
- **`/api/activities/natural` must be matched before the `/api/activities` prefix.** It is a Gemini call that happens to live under that path; falling through to `STANDARD` would give it a 100/min allowance against a third-party quota.
- **The filter runs *before* `JwtAuthenticationFilter`** on purpose, so unauthenticated traffic is limited too — which is why it reads the token itself rather than the `SecurityContext`.
- The bucket map once only ever grew; it now evicts idle buckets with a hard ceiling.
- Buckets are in memory, so limits are **per instance**. A shared store is needed before running more than one.
- The test profile raises all four to 10000. The integration suite registers and logs in from one address against a shared filter instance and would otherwise throttle itself; `RateLimitingFilterTest` constructs the filter directly with the real numbers.

## 5. Error handling

`GlobalExceptionHandler` is the single funnel for turning exceptions into HTTP responses. The handlers, in order of specificity:

- `MethodArgumentNotValidException` → **400**, field-level validation messages.
- `HttpMessageNotReadableException` / `MethodArgumentTypeMismatchException` → **400**, generic message. Jackson's own text names Java classes and parser offsets, so it is logged, not returned.
- `DataIntegrityViolationException` → **409**, with a **hand-written friendly message per known cause** — never let `ex.getMessage()` (raw SQL/Hibernate text) reach the client. When you add a new foreign key or constraint that could realistically be violated by user action, add a specific branch here with a specific message, matched on a specific substring of the root cause. Don't just add a generic catch-all message — the frontend's session-recovery logic pattern-matches on the *exact* phrase `"account that no longer exists"` to decide whether to force a logout; a careless generic FK message containing that same substring for an unrelated cause would wrongly log someone out (this exact mistake was made and fixed once — check `js/api.js`'s `forceSessionExpired` trigger condition stays narrow if you touch either side). The `fk_activity_user` branch here is now only a **backstop** for the narrow race where the row disappears between the lookup and the flush — the dead-session case proper is caught upstream by `AccountNotFoundException` below.
- `AccountNotFoundException` → **409**, message `AccountNotFoundException.MESSAGE`. Thrown when the id **from the JWT** doesn't resolve to a row — the session outlived the account, almost always because the database was reset while a browser still had a token saved. This is the case the frontend self-heals from (§8), and it has to be signalled from the *lookup*, not from a database constraint: every service loads the user before it writes anything, so the FK branch above practically never fired and the dead session was reported as a plain 400 `"User not found: …"`. That left the person on a page that looked fine but failed on every action, with no way to recover short of clearing site data. Use it **only for the caller's own id** — looking up someone *else* who doesn't exist (a challenge opponent, say) stays an ordinary `RuntimeException`/400, because the caller's own account is fine and logging them out would be wrong.
- `AccessDeniedException` → **403** (see §4).
- `RuntimeException` → **400**, but **only when the class is exactly `RuntimeException`**. The services signal expected failures with `new RuntimeException("Activity not found: …")`, so those messages are safe to return. Any *subclass* is a real fault and falls through to the handler below. Previously this was a blanket catch: an NPE or a `LazyInitializationException` was reported as a 400 carrying an internal message, which both lied to the client about whose fault it was and leaked implementation detail.
- `Exception` → **500**, logged in full, reported without detail — **except** for Spring's own exceptions that already carry a status (`NoResourceFoundException` 404, `HttpRequestMethodNotSupportedException` 405, `HttpMediaTypeNotSupportedException` 415, anything thrown as a `ResponseStatusException`). They all implement `ErrorResponse`, and the handler checks for that first. Catching `Exception` without that check turns a simple typo in a URL into a 500 that looks like the server crashed.

**When adding a service-level failure**, throw `new RuntimeException("readable message")` exactly — not a subclass — or it will be reported as a 500. The one deliberate exception is `AccountNotFoundException`: it *is* a subclass, and it stays safe only because it has its own `@ExceptionHandler`, which Spring prefers over the `RuntimeException` one. Any new subclass needs the same treatment or it becomes a silent 500.

## 6. Outbound HTTP (the Gemini call)

- **`RestClient`, not `WebClient`.** The app makes two kinds of outbound call (Gemini, and the password-reset email), both through the same shared builder. Using `WebClient` meant carrying `spring-boot-starter-webflux` and the whole reactor-netty stack — a second web framework, its event-loop threads and ~20MB of jars — purely to make a request that was immediately `.block()`ed. `RestClient` lives in `spring-web`, already on the classpath. **Do not reintroduce the webflux starter.**
- **The request factory is pinned explicitly** in `RestClientConfig` to `SimpleClientHttpRequestFactory` (`HttpURLConnection`). Left to auto-detection, with no Apache or Jetty client present, Spring selects the JDK `HttpClient` — which builds an NIO selector *eagerly in its constructor* and therefore fails at bean creation on machines where that is blocked (see §7). One occasional outbound call gains nothing from connection pooling, and starting reliably everywhere is worth more.
- **Timeouts live on the builder in `RestClientConfig`**, not in the service — both so connect and read are capped separately, and so `MockRestServiceServer` can bind to the builder in tests (it works by installing its own request factory, which a service-level `.requestFactory(...)` call would overwrite). Without a timeout, a hung upstream holds a Tomcat worker open indefinitely.
- **The API key goes in the `x-goog-api-key` header, never `?key=`.** Query strings end up in access logs, proxy logs and error reports.
- **The model is configurable** (`gemini.api.model`, env `GEMINI_MODEL`, default `gemini-flash-lite-latest`). It has been retired out from under the app twice — `gemini-1.5-flash`, then `gemini-2.0-flash` — and both times every call silently failed into the rule-based fallback. The default is now an alias Google keeps current. The env var needs its explicit `${GEMINI_MODEL:...}` binding in `application.properties`; Spring's relaxed binding maps `GEMINI_MODEL` to `gemini.model`, so without it the README's variable did nothing. Keep a Lite model unless the read timeout goes up: full Flash took ~45s against a 20s timeout.
- **Read the response defensively.** Gemini returns HTTP 200 with an *empty* `candidates` array when a prompt is blocked or quota is exhausted. Indexing straight into `candidates[0]` turned that into a bare NPE that said nothing about the cause. Log `promptFeedback.blockReason` and the response body on `RestClientResponseException` — that body is the actual diagnostic.

- **Email goes over HTTPS, not SMTP.** Render blocks outbound SMTP ports on the free tier, so `spring-boot-starter-mail` would compile, pass every test and then silently deliver nothing on the deployed instance. `EmailService` posts to an email API (`app.mail.api-url`) through the same `RestClient`, and degrades exactly like Gemini does: with no key it logs the reset link rather than failing the request.

## 7. Testing (Spring Boot 4.1 specifics)

Spring Boot 4.1 relocated several test-support classes to separate artifacts that may not be resolvable depending on the local Maven setup. Two were hit directly:

- **`@AutoConfigureMockMvc` / `MockMvc`** — moved, caused compile failures. **Do not reintroduce.**
- **`TestRestTemplate`** — same issue. **Do not reintroduce.**

Integration tests in this repo use plain `java.net.http.HttpClient` (built into the JDK, zero extra dependency, works regardless of Spring Boot test-artifact churn):

```java
private final HttpClient http = HttpClient.newHttpClient();
HttpResponse<String> resp = http.send(
    HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
    HttpResponse.BodyHandlers.ofString());
```

> **Known environment trap:** on some Windows machines `Selector.open()` fails with `java.io.IOException: Unable to establish loopback connection` (the JDK's NIO self-pipe connects over AF_UNIX, and a security product or Winsock LSP can block it). Plain sockets still work, so it isn't obvious. When that happens, **nothing that needs NIO can run locally** — `java.net.http.HttpClient`, embedded Tomcat, and therefore both `*IntegrationTest` classes *and* `./mvnw spring-boot:run`. The usual fix is `netsh winsock reset` plus a reboot. CI (Linux) is unaffected. Don't mistake this for a code regression — the tell is that the stack trace bottoms out in `sun.nio.ch.PipeImpl$Initializer`.

Other testing rules:

- **`FitnessMonolithApplicationTests` uses `WebEnvironment.MOCK`, deliberately.** It exists to prove the whole context wires up — every controller, service, repository, filter and `@Bean`, plus Spring Data validating the derived query method names and Hibernate generating the schema. It does not need to serve HTTP (the two `*IntegrationTest` classes do that on a random port), and starting a real Tomcat only made it unrunnable wherever the server can't bind. **This is the fastest way to catch a startup-time failure** — a missing bean, a bad `@Value`, a typo'd query method — so run it after any wiring change.
- Integration tests use `@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)` + `@ActiveProfiles("test")` (H2, see `application-test.properties`) — never point integration tests at real MySQL.
- Use `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` with `@BeforeAll` (non-static) whenever tests share setup state (a registered user, a JWT, a seeded activity). Ordering tests with `@Order` alone and passing state through instance fields between separately-ordered `@Test` methods is fragile — a `@BeforeAll` that does the setup once, before any test runs, is far more reliable and was the fix after two rounds of flaky "activity not found" failures.
- Mockito: `@Mock` + `@InjectMocks` + `@ExtendWith(MockitoExtension.class)` for service unit tests — no Spring context, no DB.
- **Outbound HTTP is tested with `MockRestServiceServer.bindTo(RestClient.Builder)`** — no network, no API key. See `GeminiServiceTest`. Services that make outbound calls take the *builder*, not a built client, so a test can bind to it.
- Services take the caller's user id as a parameter (§2) specifically so authorization can be unit-tested without a `SecurityContext`. `CurrentUser` itself is tested by setting `SecurityContextHolder` directly.

## 8. Frontend (`frontend/js/`) patterns

- **It's native ES modules, so two things that used to be free now aren't:**
  - **No inline handlers — use `data-action`.** A button says what it does with `data-action="delete-goal" data-id="…"`, a form with `data-submit="…"`, a select with `data-change="…"`, in `index.html` *and* in HTML a panel builds with `innerHTML`. The name is mapped to a function in the `registerActions(...)` lists at the bottom of `js/main.js`; arguments come from the element's own `data-*` attributes. `js/actions.js` routes the events with one delegated listener each, so rows rendered later need no wiring. Never add `onclick=` back: the Content-Security-Policy in `index.html` blocks inline handlers (and inline `<script>`), and that block is the point. `tests/markup.test.mjs` fails on any inline handler, on a `data-action` name with no handler, and on a handler nothing uses. Nothing goes on `window`.
  - **Destructive actions ask with `await confirmDialog({ title, message, confirmLabel })`** from `ui.js`, never `window.confirm()`. It resolves `true` only for the confirm button; Cancel, ✕, Escape and the backdrop all resolve `false`. Pick labels that can't be confused: when the action is itself "Cancel …", pass `cancelLabel` ("Keep it").
  - **`renderPager()` takes the page-loading function itself**, not its name.
  - **An imported binding is read-only.** State that more than one module reassigns lives on the `session` object in `js/state.js` (`session.accessToken`, `session.refreshToken`, `session.currentUser`, `session.expiredInProgress`) — assign to its properties, never re-declare it as a module `let`. State only one module touches (a panel's cache, `currentPanel`) stays a `let` in that module.
  - The page must be served over HTTP (`python -m http.server`); browsers won't load modules from `file://`.
  - **CSP.** `script-src` is `'self'` plus `cdn.jsdelivr.net` (Chart.js), with no `'unsafe-inline'`. A new external script host has to be added to the meta tag. `style-src` keeps `'unsafe-inline'` because of the ~150 `style=""` attributes. `connect-src` allows any `https:` origin plus `localhost`/`127.0.0.1` on any port, because the Render URL isn't known when the page is written; an `http://` backend on a LAN IP would be blocked. That breadth is why `config.js` must never take the backend URL from the page's address on a deployed origin (next section).
  - **Every backend `fetch` goes through `wakeAwareFetch()`** (`wake.js`; `api()` already does). That is what shows the "waking up the server" notice when a sleeping Render instance is slow to answer. It is armed only when nothing has been heard from the server for 14 minutes, so a slow Gemini call on a warm server doesn't trigger it, and never on localhost.
  - **Tests:** `cd frontend && node --test "tests/*.test.mjs"` (Node 22+, no npm). `tests/helpers/env.mjs` is a small fake DOM, so the real modules import unchanged. The Selenium suite, `tests/e2e/regression_selenium.py`, needs a live backend; its docstring has the setup.

- **Every backend call goes through the single `api()` wrapper** — never call `fetch()` directly elsewhere. It handles: attaching the JWT header, silent token refresh on 401, and self-healing on a dead session. Bypassing it means losing all three.
- **Session self-healing, two triggers:**
  - A **409** whose message contains `"account that no longer exists"` → `forceSessionExpired()`. Match that account-specific phrase only; a 409 about some *other* missing record shouldn't log anyone out. The backend raises this from `AccountNotFoundException` (§5) — if you change that message, keep the phrase intact or self-healing silently stops working. `DeadSessionRecoveryIntegrationTest` pins both halves of this contract.
  - The **boot check** (`DOMContentLoaded` → `GET /api/profile/{id}`) covers the returning visitor; the 409 above covers a tab that was *already open* when the database went away. Its `catch` bails out early if `session.expiredInProgress` is already set, so `api()`'s more specific message isn't overwritten by the generic one.
  - A **401 that survives a refresh attempt** (or with no refresh token left to try) → also `forceSessionExpired()`. This covers a revoked session, a refresh token past its 30 days, or a changed server JWT secret. Without it every panel just showed "Authentication required" with no way forward.

  Both set `session.expiredInProgress = true` for 1.5s so the *other* in-flight calls from the same failed page load (e.g. the 5 parallel calls in `loadOverview()`'s `Promise.all`) don't each pop their own redundant toast. If you add a new page that fires multiple parallel `api()` calls, this guard already covers it — don't add per-call toast suppression elsewhere.
- **Never send `new Date().toISOString()` to a `LocalDateTime` field.** Use `localDateTimeNow()`. `toISOString()` returns UTC with a trailing `Z`, and Jackson parses that into a `LocalDateTime` by *silently dropping the Z* — no error, just wrong data. A workout logged at 21:00 IST was stored as 15:30, which surfaced much later as "your peak workout time" in Stats & Insights being off by exactly the UTC offset.
- **Logout sends `{ refreshToken }`, not `{ userId }`** — see §2.
- **On a deployed origin `API_BASE` is always `DEPLOYED_BACKEND_URL`.** Only on `localhost`/`127.0.0.1` does it resolve `?api=<url>` → a remembered `localStorage` override → `http://localhost:8080`. The override used to work on the deployed page too, which made it a hijack: one link with `?api=https://attacker` sent the visitor's password on their next login, and their access and refresh tokens at once if signed in, and it was remembered for every later visit. The deployed page now also erases a remembered value. `tests/config.test.mjs` pins this; don't reintroduce a URL- or storage-driven backend on a deployed origin. Don't hardcode `localhost:8080` in new code — read `API_BASE`.
- **Put only IDs in `data-*` attributes, never free text** (titles, descriptions, user-entered strings). Interpolated markup is an injection surface the moment one `escapeHtml()` is missed. Pass the id and look up or re-fetch any display text inside the handler: `openEditMeal(id)` reads `nutritionMealsById`, and `openChallengeDetail(challengeId)` re-fetches.
- **`escapeHtml()` wraps every piece of server-returned or user-entered text before it's inserted via `innerHTML`.** Don't skip it for "obviously safe" fields (notes, descriptions, titles) — user input is exactly what it's for.
- **`toast()` and `showAuthError()` take plain text and set `textContent`.** Never pass them HTML, and never switch either back to `innerHTML`. Server messages go straight into toasts, and some carry another user's name. `toast()` used to use `innerHTML`, which was a live cross-user XSS (§10).

## 9. Data access performance

Anything that runs on **every write** must not scan a user's whole history. Two did:

- `RecommendationService.maybeGenerateAfterActivity` loaded every activity a user had ever logged just to call `.size()` on it, on every single activity save — so logging a workout got steadily slower the longer someone used the app. Use `countByUserId`.
- The same method then loaded them *all again* and sorted in memory to take the last 5. Use `findTop5ByUserIdOrderByStartTimeDesc`.
- `StatsService.getAllTimeStats` loaded every activity to sum two columns. Use the `SUM` queries on the repository.

Rule of thumb: if you're writing `.stream()` over a repository result to count, sum, sort or limit it, that belongs in the query. Spring Data derives `countBy…`, `findTopN…OrderBy…` for free.

## 10. Known historical bugs (don't reintroduce these)

| Bug | Where | Lesson |
|---|---|---|
| Every controller trusted a client-supplied `userId` | all 12 controllers | Any logged-in user could read or modify anyone else's data. Identity comes from the JWT — see §2. |
| `RegisterRequest.role` written straight to the saved user | `dto/RegisterRequest` | A public endpoint that lets the caller pick their own role is an admin-account generator. |
| `/api/auth/logout` accepted a bare `userId` | `AuthController` | A permitAll endpoint that acts on a named user lets anyone log anyone out. |
| `hasRole("ADMIN")` could never match | `JwtAuthenticationFilter` | `hasRole("X")` requires the authority `ROLE_X`. A security rule that silently never matches looks identical to one that works. |
| Missing/expired token returned 403, not 401 | `SecurityConfig` | The frontend only refreshes on 401 — a wrong status code broke session renewal invisibly. |
| `validateJwtToken` had `return true` outside the try block | `JwtUtils` | Any early/misplaced `return` inside exception-handling code deserves a second look — it can silently defeat the entire check. |
| JWT secret hardcoded in source | `JwtUtils` | Secrets always come from env vars, never a literal in code, even during prototyping. |
| Password field present in `UserResponse` | `dto/UserResponse` | Audit every response DTO for fields that shouldn't leave the server. |
| `new Date().toISOString()` sent to a `LocalDateTime` | frontend | Jackson drops the trailing `Z` silently. Every timestamp was UTC-shifted with no error anywhere. |
| `toast()` rendered its message with `innerHTML` | `js/ui.js` | Stored XSS. Register with the name `<img src=x onerror=…>`, send a friend request, and the script runs in whoever accepts it ("You're now friends with <name>"), where it can read their tokens from `localStorage`. Reproduced in Chrome, then fixed with `textContent`. `RegisterRequest.firstName` still accepts any string. |
| Weekly goals accumulated forever | `GoalService` | Progress was read with `findByUserIdAndIsCompletedFalse`, so a completed goal was filtered out and could never be reset. |
| Challenge scores only updated at 23:55 | `ChallengeService` | A scheduled job as the *only* writer means the UI shows stale data all day. Active challenges recompute on read. |
| Rate limiter keyed on the access token | `RateLimitingFilter` | Tokens rotate — the limit reset itself on every refresh, and the bucket map grew forever. |
| `gemini-1.5-flash` hardcoded | `GeminiService` | A retired model fails every call, and the graceful fallback hid it. Make the model configurable. |
| Gemini API key in the query string | `GeminiService` | Credentials in URLs end up in logs. |
| `ddl-auto=update` left three FKs pointing at a dropped legacy `user` table | MySQL schema (`activity`, `recommendation`) | `ddl-auto=update` only ever ADDS constraints — it never corrects one that already exists. After the entity moved from `user` to `fitness_user`, `fk_activity_user` still referenced the old (empty) table, so **every** activity insert failed with a constraint error and the activity table stayed permanently empty. It survived every restart, and the friendly message blamed the user's session. When a write fails for every user including brand-new ones, suspect the schema, not the session — and check `information_schema.KEY_COLUMN_USAGE` for what the FKs actually reference. This was the strongest argument for Flyway, which has since landed: the schema is now built from `db/migration` and `ddl-auto` is `validate`, so a mapping that disagrees with the database fails at startup instead of being silently patched around. |
| Dead-session recovery keyed on a foreign-key constraint name | `GlobalExceptionHandler` / all user lookups | The 409 the frontend self-heals from was only raised by a `fk_activity_user` violation — which never happened, because every service loads the user *before* it writes. The real dead session fell through as a 400 and the recovery code could never run. Derive a condition from the thing you actually know (the authenticated id didn't resolve), not from a downstream symptom that a guard upstream already prevents. |
| Blanket `RuntimeException → 400` | `GlobalExceptionHandler` | Genuine server faults were reported as client errors, carrying internal messages. |
| Custom `ObjectMapper` bean re-enabled `FAIL_ON_UNKNOWN_PROPERTIES` | `JacksonConfig` | A hand-built `ObjectMapper` does **not** inherit Spring Boot's defaults. Any extra field in a request body became a hard 400, so removing a DTO field broke every client still sending it. **Boot 4.1 update (PLAN P4-20):** the web layer now uses Boot's own Jackson 3 mapper, not this bean, so this bean only affects code that injects the Jackson 2 `ObjectMapper` by hand (`GeminiService`, `WeeklyDigestService`). Unknown request fields are ignored by Jackson 3's defaults; `LeftoversIntegrationTest` pins that. |
| CI Docker smoke test ran the container against H2 | `ci.yml` | `h2` is `test`-scoped — it isn't in the runtime image. The container could never have started. |
| No Maven wrapper committed | repo root | `Dockerfile`, CI and every doc called `./mvnw`, which didn't exist. The Docker build and CI were broken as committed. |
| `userId` trusted from a request header | `ActivityController` | The original version of the §2 bug. |
| `WebClient.Builder` / `ObjectMapper` not injectable | Spring Boot 4.1 | These stopped being auto-registered; both have explicit `@Bean` methods in `config/`. If a future upgrade breaks a different "obviously auto-configured" bean, the fix is the same shape. |
| `@JsonIgnore` on `WorkoutPlan.days` | `model/WorkoutPlan` | Silently removed data from every API response with no error anywhere — the bug was "the frontend feature does nothing" with zero exceptions thrown. `@JsonIgnore` is invisible at the call site; grep for it specifically when a field seems to mysteriously never appear in a response. |
| Bucket4j `Bandwidth.classic()` / `Refill` deprecated | `RateLimitingFilter` | Third-party API surfaces move between major versions; the fix was the modern `Bandwidth.builder()...build()` chain. |
| CI smoke test shell logic (`A \|\| echo B && C`) silently passed on failure | `.github/workflows/ci.yml` | Bash operator precedence in one-liners is a common trap — prefer an explicit `if ! cmd; then ...; exit 1; fi`. |
| CI referenced `/actuator/health` before the Actuator dependency existed | `pom.xml` / `ci.yml` | A workflow step can reference an endpoint that was never actually wired up, and nothing catches it until the workflow runs for real. |

## 11. Adding a new feature — the established pattern

1. Entity in `model/` (with `@Builder.Default` on every field with an inline initializer)
1b. **A migration in `db/migration` for any schema change it implies.** `ddl-auto` is `validate`, so the app will not start without one, and `FlywayMigrationTest` will fail naming the column. The reliable way to write it is to let Hibernate generate the DDL for MySQL and diff it against the previous schema, rather than transcribing by hand — that is how V1 and V2 were produced. When a migration has to transform data in a way MySQL and H2 spell differently (V10 hashes existing refresh tokens: `SHA2()` on one, `HASH()` on the other), write it as a Java migration in `src/main/java/db/migration/` instead of reaching for `db/vendor`; Flyway finds it on the same `classpath:db/migration` location, and it runs on the H2 the tests migrate too. Keep it self-contained (no application classes), since a migration must keep doing exactly what it did.
2. Repository interface in `repository/` — derive `countBy…` / `findTopN…` rather than filtering in Java (§9)
3. Service in `service/` — `@Transactional`/`@Transactional(readOnly = true)` on anything touching a lazy relationship (§3); take the caller's user id as a parameter and ownership-check any resource loaded by id (§2)
4. Controller in `controller/` — thin, delegates immediately to the service, and resolves identity through `CurrentUser` (§2)
5. If it needs a UI: add a sidebar link + `<section class="panel" id="panel-x">` in `index.html`, put its code in `js/panels/x.js`, register it in `panelLoaders`/`panelTitles` in `js/nav.js`, add anything its inline handlers call to the `window` list in `js/main.js` (§8), write the render function following the existing card patterns (`.chc`, `.ac`, `.card` + `.clbl` are the established building blocks — reuse them, don't invent new visual patterns per feature)
6. Unit test the service (Mockito), including an "another user can't touch this" case. If it's a full user-facing flow, add it to the relevant integration test's `@BeforeAll`/`@Test` sequence rather than creating a new integration test class per feature. Run `FitnessMonolithApplicationTests` to catch wiring mistakes (§7).
