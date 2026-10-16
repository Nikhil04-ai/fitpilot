# FitPilot 🏋️ — Guide Your Fitness Journey

An AI-powered fitness tracking platform with a Spring Boot backend and a vanilla JS frontend — built as a full production-style app: JWT auth, Gemini AI coaching, muscle recovery tracking, workout plans, challenges, and more.

## Tech Stack

**Backend:** Java 21 · Spring Boot 4.1 · Spring Security (JWT) · Hibernate/JPA · MySQL · Gemini AI · Docker
**Frontend:** Vanilla HTML/CSS/JS (no build step, no framework)
**Testing:** JUnit 5 · Mockito · H2 (in-memory test DB) · 128 tests
**CI/CD:** GitHub Actions

## Features

- JWT authentication with refresh tokens
- Auto calorie calculation (MET-based formula)
- Activity streak tracking with personal bests
- AI-powered coaching recommendations (Gemini)
- Natural language activity logging ("I ran 5km in 30 minutes")
- Muscle recovery tracking (48-hour windows per muscle group)
- Pain point logging with injury pattern detection
- Structured workout plans (enroll, track daily progress)
- Weekly/monthly stats, peak workout time, plateau detection
- 1-on-1 fitness challenges (calories, workouts, duration, streaks)
- BMI, hydration, and sleep tracking
- Rate limiting, pagination, and full API documentation (Swagger)

## Project Structure

```
fitpilot/
├── backend/          Spring Boot REST API
│   ├── src/
│   ├── pom.xml
│   └── Dockerfile
├── frontend/          Static HTML/CSS/JS client
│   ├── index.html
│   └── js/            Native ES modules (main.js is the entry point)
├── render.yaml        Render deployment blueprint
└── .github/workflows/ CI pipeline (build, test, coverage, Docker)
```

## Running Locally

### Backend

```bash
cd backend
# Create a MySQL database first: CREATE DATABASE fitness_demo;

# Set these environment variables (in IntelliJ: Run → Edit Configurations → Environment Variables)
# DB_URL=jdbc:mysql://localhost:3306/fitness_demo
# DB_USER=root
# DB_PWD=your_mysql_password
# JWT_SECRET=<a long random Base64 string>
# GEMINI_API_KEY=<optional — get one free at https://aistudio.google.com/app/apikey>

./mvnw spring-boot:run
```

Backend runs on `http://localhost:8080`. Swagger docs at `http://localhost:8080/swagger-ui/index.html`.

### Frontend

```bash
cd frontend
python3 -m http.server 5500
```

Open `http://localhost:5500`. Double-clicking `index.html` no longer works: the JS is
native ES modules, which browsers won't load from `file://`. It talks to the backend
automatically at `localhost:8080` when running locally.

When deployed, it calls `DEPLOYED_BACKEND_URL` from the top of `js/config.js`, and only
that: to point the deployed site at a different backend, edit that line and redeploy.
Locally you can still load the page with `?api=http://localhost:9090` to try another
backend (it is remembered in `localStorage`). The deployed page ignores `?api=`, because a
link carrying it used to send visitors' passwords and tokens to whatever host it named.

## Running Tests

```bash
cd backend
./mvnw test
```

Uses H2 in-memory database — no MySQL needed for tests.

## Configuration

| Env var | Default | Notes |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PWD` | — | Required. MySQL connection. |
| `JWT_SECRET` | — | Required. Long random Base64 string. |
| `PORT` | `8080` | Injected by Render; never hardcode. |
| `FRONTEND_URL` | _(blank)_ | Deployed frontend origin, added to the CORS allow-list. |
| `GEMINI_API_KEY` | _(blank)_ | Optional — AI features degrade gracefully without it. |
| `GEMINI_MODEL` | `gemini-flash-lite-latest` | Swap without a code change when a model is retired. |
| `GEMINI_TIMEOUT_SECONDS` | `20` | Read timeout on the AI call (connect timeout is half this). |
| `SHOW_SQL` | `false` | Set `true` locally to log every SQL statement. |
| `MAIL_PROVIDER` | `brevo` | `brevo` or `resend`. See [Password reset email](#password-reset-email). |
| `MAIL_API_KEY` | _(blank)_ | Without it, reset links are written to the backend log instead of emailed. |
| `MAIL_FROM` | _(blank)_ | The verified sender, e.g. `FitPilot <you@gmail.com>`. Required once a key is set. |

## Password reset email

"Forgot password" needs an email provider. Without one, the backend writes each reset
link to its own log and emails nothing. The page still says "a reset link is on its way",
because it must never reveal which addresses have accounts. So check the backend's
startup log, which says in one line whether email is on:

```
Email is OFF (MAIL_API_KEY not set): password reset links will be written to this log …
Email is ON via Brevo from you@gmail.com
```

**Setting up Brevo** (free, 300 emails a day, no domain needed):

1. Sign up at [brevo.com](https://www.brevo.com).
2. Under **Senders, Domains & Dedicated IPs → Senders**, add the address to send from,
   for example your Gmail, and click the verification link Brevo emails you.
3. Under **SMTP & API → API Keys**, create a key. It starts with `xkeysib-`.
4. Put these in `backend/local.properties` locally, or in the Render environment when
   deployed, then restart the backend:
   ```
   MAIL_PROVIDER=brevo
   MAIL_API_KEY=xkeysib-...
   MAIL_FROM=FitPilot <the-address-you-verified@gmail.com>
   ```

**When deployed, also set `FRONTEND_URL`** to the frontend's address, because the emailed
link points there. Left blank, it points to `http://localhost:5500`.

**If a reset email doesn't arrive**, the backend log has the provider's own reason, on a
line starting `Email provider Brevo returned`. The usual causes are:
- an unverified `MAIL_FROM`
- a mistyped key
- the email sitting in the recipient's spam folder

Resend (`MAIL_PROVIDER=resend`) also works. Without a domain of your own, though, it only
delivers to the address its account was opened with.

## Security model

Two rules the API depends on — worth knowing before adding an endpoint:

1. **Identity always comes from the JWT, never from the request.** Controllers read the
   caller's id via `CurrentUser.id()`. Endpoints whose URL still carries a `{userId}`
   segment pass it through `CurrentUser.requireSelf(userId)`, which rejects a mismatch
   with 403; a `userId` in a request body is accepted for backwards compatibility and
   ignored. Anything addressed by resource id alone (an activity, a goal, a challenge)
   is ownership-checked in its service before being read or written.
2. **Roles are assigned server-side.** Registration always creates a plain `USER` —
   there is deliberately no `role` field on `RegisterRequest`.

Auth failures are distinguished on purpose: **401** means "no/expired/invalid token"
(the frontend refreshes and retries transparently), **403** means "valid token, not your
data" (refreshing won't help). Getting these the wrong way round silently breaks the
frontend's session handling.

## Deployment

- **Backend:** Deployed on [Render](https://render.com) (free tier) using the included `Dockerfile` / `render.yaml`
- **Database:** [Aiven](https://aiven.io) free MySQL
- **Frontend:** [GitHub Pages](https://pages.github.com), served directly from `/frontend`

## License

Personal portfolio project — feel free to fork and adapt.
