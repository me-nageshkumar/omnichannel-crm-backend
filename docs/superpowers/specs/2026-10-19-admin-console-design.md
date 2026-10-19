# Design: Admin console, first slice (super admin login, dashboard, profile)

Status: draft 2026-10-19. Spans two repos: `whatsapp-backend` (Spring Boot, `me.nageshk.omnichannel`)
and `omnichannel_crm_frontend` (new, empty, remote `git@github.com:me-nageshkumar/omnichannel_crm_frontend.git`).
Builds on `2026-10-19-users-and-two-factor-login-design.md`, which is merged.

## Intent

Give the super admin (nageshk) a browser console: log in (with a code if two-factor is on), land on
a dashboard, open a profile page, log out. Two-factor for the super admin becomes opt-in, enabled
from the profile page with install-and-setup help. This slice sets up the frontend repo, the build,
and serving from the Spring Boot jar, so later slices only add screens and endpoints.

## Goals

- React + PatternFly frontend started from `patternfly-react-seed`, served by the Spring Boot jar on
  one port in production.
- Login screen handling both login answers: token, or two-factor code needed.
- Only SUPER_ADMIN can use the console.
- Dashboard landing page, masthead with user menu (Profile, Log out).
- Profile page: the super admin's details and two-factor status, enable with app-install and setup
  help, disable.

## Non-goals (this slice — discussed later)

- Console access for any role other than SUPER_ADMIN; role- and authority-based access in general,
  including which roles may call the WhatsApp endpoints.
- Users list, create user, edit, deactivate/reactivate.
- First-login setup (forced password change, forced two-factor enrolment), the
  `mustChangePassword`/`mustEnrolTwoFactor` fields and the `SETUP_PENDING` token.
- Editing profile details, changing password, forgot-password.
- Dashboard content (charts, channels, WhatsApp screens).
- Refresh tokens or cookie-based sessions.

## Anti-goals

- Breaking the Chrome extension: `POST /api/auth/login` for the `wabridge` device user keeps
  returning `{ token, expiresAt }`, and the WhatsApp endpoints keep accepting its token.
- Password hashes or TOTP secrets in any API response other than the one-time `initiate` reply.
- Frontend requiring the backend repo to build, or backend tests requiring the frontend.

## Decided by the user

- React, PatternFly (patternfly.org guidelines, no custom mockups), start from patternfly-react-seed.
- This slice: only the super admin logs in; dashboard with logout and a profile page.
- Super admin seeded with two-factor **off** by default; can enable it from the UI.
- The enable-two-factor screen shows which iOS/Android app to install and the steps to configure it.
- Production: frontend served as static files by the Spring Boot app on one port.

## Backend changes (`whatsapp-backend`)

### Seeder

- `omnichannel.super-admin.totp-secret` and `OMNICHANNEL_SUPER_ADMIN_TOTP_SECRET` are removed. The
  super admin is always seeded with `twoFactor = disabled()`.
- Local `omnichannel_crm.users` is dropped and reseeded once by hand (it holds throwaway smoke data).

### Current user

`GET /api/users/me` ─► 200 `{ id, name, username, email, mobileNumber, role, twoFactorEnabled }`.
The frontend uses it after login, and on every app load, to show the profile and to enforce the
super-admin-only rule. It does not rely on the token's `twoFactorEnabled` claim, which goes stale.

### Wrong code on two-factor endpoints

`POST /api/users/me/two-factor/confirm` and `/disable` with a wrong code change from 401 to
**400 `{ code: "INVALID_OTP", message }`**. In the console, 401 means "your session is invalid"
and logs you out, so a mistyped code must not return it. Lockout stays 429. Login and verify-otp
are unchanged.

### Serving the frontend

- Static resources come from `classpath:/static/` (the Spring Boot default). A `GET` for any path
  outside `/api` and `/ws` that has no file extension forwards to `/index.html`, so deep links and
  refresh work. An unknown `/api/**` path still returns the JSON error, not the page.
- Security: `GET` on static files, `/`, `/index.html` and those SPA routes is `permitAll`.
  `/api/**` stays authenticated except `/api/auth/**`.
- Maven profile `with-frontend` copies `${frontend.dist}` (default
  `${project.basedir}/../omnichannel_crm_frontend/dist`) into `target/classes/static` during
  `process-resources`, and fails the build if `index.html` is missing. It does not run npm. The
  default build and tests are unaffected.

## Frontend (`omnichannel_crm_frontend`)

### Starting point

- First commit: a copy of `patternfly-react-seed` at commit `6fd01f5`. That gives MIT licence,
  PatternFly 6.5.1, React 18, strict TypeScript, React Router 7, Webpack, Vitest + Testing Library,
  ESLint and Prettier. Keep its `AGENTS.md` conventions and `npm run ci-checks`.
- From the second commit on: rename to "Omnichannel CRM", and remove the placeholder pages (Support,
  Settings General/Profile) and the PatternFly branding.

### Development

- `npm run start:dev` runs on port 9000. The webpack dev server proxies `/api` and `/ws` to
  `BACKEND_URL` (default `http://localhost:8080`), so no CORS is needed in development.

### Session handling

- The access token, its `expiresAt`, and an in-progress `twoFactorToken` live in `sessionStorage`.
  They survive a refresh and die with the tab.
- One API client (a `fetch` wrapper) adds the bearer token. Any 401 from a non-auth call clears the
  session and routes to `/login` with a "Your session has expired" alert. The same happens when
  `expiresAt` passes.
- After a successful login the app calls `GET /api/users/me`. If `role` is not `SUPER_ADMIN`, the
  session is cleared and the login page shows "This console is only available to the super admin."
  This is a console rule only; server-side role rules come with the later roles discussion.
- Log out, in the masthead user menu, clears the session on the client. There is no server call.

### Routes and screens

PatternFly components are named per the PatternFly guidelines.

| Path | Screen |
|---|---|
| `/login` | `LoginPage` + `LoginForm` titled "Omnichannel CRM". Inline errors for invalid credentials, inactive account, lockout (shows minutes until retry), and not super admin. |
| `/login/verify` | `LoginPage` with a single 6-digit code field and a back link to `/login`. An expired pending token returns to `/login` with a message. |
| `/` | Dashboard: `PageSection` with title "Dashboard" and an `EmptyState` reading "Welcome, <name>". Placeholder for later content. |
| `/profile` | Profile page (below). |
| `*` | `NotFound` |

Everything except `/login*` requires a session; without one the app redirects to `/login` and then
back. The app shell is the seed's `AppLayout` with masthead, sidebar nav and page. The nav shows
Dashboard and Profile. The masthead user dropdown shows the name, Profile and Log out.

### Profile page

- **Details card** (read-only `DescriptionList`): name, username, email, mobile, role.
- **Two-factor card.**
  - When off: a short explanation and an **Enable two-factor** button, which opens the enable panel
    inline.
  - The enable panel has three numbered steps:
    1. **Install an authenticator app.** Either Google Authenticator
       ([App Store](https://apps.apple.com/app/google-authenticator/id388497605),
       [Google Play](https://play.google.com/store/apps/details?id=com.google.android.apps.authenticator2))
       or Microsoft Authenticator
       ([App Store](https://apps.apple.com/app/microsoft-authenticator/id983156458),
       [Google Play](https://play.google.com/store/apps/details?id=com.azure.authenticator)).
    2. **Add your account.** Open the app, tap **+**, choose **Scan a QR code**, and scan the code
       shown. The QR image comes from `initiate`. An `ExpandableSection` titled "Can't scan the
       code?" shows the secret in groups of four, copyable through `ClipboardCopy`, with the
       account (username) and the issuer "Omnichannel CRM", and tells you to choose time-based.
    3. **Enter the 6-digit code** the app shows. This calls `confirm`, shows a success `Alert`, and
       flips the status to On. **Cancel** closes the panel without saving.
  - When on: an "Enabled" `Label`, plus a **Disable** button that opens a `Modal` asking for a
    current code.
  - Inline errors for a wrong code (400 `INVALID_OTP`) and lockout (429).
- Loading uses `Spinner` / `Skeleton`. A failed fetch shows an inline danger `Alert` with retry.

### Testing

- Vitest + Testing Library, with `fetch` mocked per test (`vi.fn`). Cover:
  - routing for both login answers;
  - errors on the code screen;
  - rejection of non-super-admins;
  - a 401 redirecting to login, and expiry;
  - the protected-route redirect;
  - rendering of the profile details;
  - the enable flow: initiate, wrong code, then success;
  - the disable flow.
- A green `npm run ci-checks` is the bar.

## Order of work

1. **Backend plan** (TDD, embedded Mongo): seeder change, `GET /api/users/me`, the 400 for a wrong
   code, SPA serving, and the `with-frontend` profile.
2. **Frontend plan**: seed import, shell, session and API client, login and verify, dashboard,
   profile.
3. **Manual end-to-end**: build the frontend, run `mvn -Pwith-frontend package`, and run the jar
   against a reseeded local Mongo. Then log in without two-factor, enable it on Profile, log out,
   log in with a code, and disable it again.

## Known gaps carried forward (from the builder check)

- Any valid access token can call the WhatsApp endpoints and the WebSocket. Today the only users
  are the super admin and the device user, so this is acceptable for now. It must be settled in the
  roles discussion, before any other role can log in.
- The device user could, in principle, log in through the API and enable two-factor via
  `/api/users/me/two-factor/*`. That would break the extension. The console rejects it, but the
  API does not. This also belongs to the roles discussion.

## Left to the builder

- Class, component and file names, following each repo's existing conventions.
- How the SPA forward is implemented (controller or resource resolver), within the rule above.
- Exact wording of help text and alerts beyond what is quoted above.
