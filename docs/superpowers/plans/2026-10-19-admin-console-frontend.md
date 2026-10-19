# Admin Console — Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** First console slice in `omnichannel_crm_frontend`: super-admin login (with optional 6-digit code), dashboard, profile with two-factor enable/disable and app-install help, server-side logout.

**Architecture:** Start from patternfly-react-seed. A small API layer (`fetch` wrapper) and a `localStorage` session store sit under a `SessionProvider` React context that owns sign-in, the super-admin check, expiry, logout and cross-tab sync. Routes are guarded by a `RequireSession` wrapper; pages are PatternFly 6 components.

**Tech Stack:** React 18, TypeScript (strict), PatternFly 6.5.1 (`@patternfly/react-core`, `-icons`, `-styles`), React Router 7, Webpack 5 + webpack-dev-server, Vitest + Testing Library + user-event, ESLint, Prettier.

**Spec:** `/Users/nagesh/Projects/WhatsAppWebJS/SCM/whatsapp-backend/docs/superpowers/specs/2026-10-19-admin-console-design.md` (frontend sections; backend is merged on `whatsapp-backend` main).

**Repo:** `/Users/nagesh/Projects/WhatsAppWebJS/SCM/omnichannel_crm_frontend` (no commits yet; remote `git@github.com:me-nageshkumar/omnichannel_crm_frontend.git`). All paths below are relative to it. Do not push.

## Global Constraints

- No new runtime dependencies. The QR code is the backend's `qrCodeDataUri` in an `<img>`; copy uses PatternFly `ClipboardCopy`.
- PatternFly components and guidelines only; no custom CSS beyond `app.css` spacing tweaks.
- App name everywhere: **"Omnichannel CRM"**. Document titles: `"<Page> | Omnichannel CRM"`.
- `npm run ci-checks` (type-check + lint + tests with coverage) green at the end of every task. ESLint treats unused imports/variables as errors.
- Imports use the `@app/*` alias.
- `localStorage` key `omnichannel.session` holding `{"token": string, "expiresAt": ISO string}`. Every read/write in try/catch; unreadable or expired ⇒ no session.
- The pending two-factor token is never persisted (React state only).
- Backend contract (exact):
  - `POST /api/auth/login {username,password}` → 200 `{token, expiresAt}` | 200 `{twoFactorRequired:true, twoFactorToken}` | 401 `{error:"invalid_credentials"|"account_inactive"}` | 429 `{error:"too_many_attempts", retryAfterSeconds}`.
  - `POST /api/auth/verify-otp` (Bearer twoFactorToken) `{otp}` → 200 `{token, expiresAt}` | 401 `{error:"invalid_otp"|"invalid_token"|"invalid_credentials"}` | 429 as above.
  - `POST /api/auth/logout` (Bearer) → 204.
  - `GET /api/users/me` → `{id,name,username,email,mobileNumber,role,twoFactorEnabled}`.
  - `POST /api/users/me/two-factor/initiate` → `{secret,qrCodeDataUri,issuer,username}`; `confirm {secret,otp}` → 204; `disable {otp}` → 204; wrong code 400 `{code:"INVALID_OTP"}`; 429 `{code:"TOO_MANY_ATTEMPTS", retryAfterSeconds}`; 409 `{code:"TWO_FACTOR_STATE"}`.
  - Any protected call with a bad/expired/revoked token → 401 (body may be empty).
- User-facing messages (exact copy):
  - invalid_credentials: "Invalid username or password."
  - account_inactive: "This account is inactive."
  - too_many_attempts: "Too many attempts. Try again in N minute(s)." where N = ceil(retryAfterSeconds / 60) ("minute" when N = 1)
  - invalid_otp (login and profile): "That code is not valid. Try again."
  - invalid_token on verify: "Your sign-in took too long. Please log in again."
  - not super admin: "This console is only available to the super admin."
  - session expired / 401: "Your session has expired. Please log in again."
  - logged out in another tab: "You were logged out in another tab."

## Review Focus

1. A stored session whose `expiresAt` already passed (browser reopened next day) must land on `/login` with the expired message, never flash the dashboard or loop — test in Task 3 and Task 5.
2. Corrupt or foreign data under `omnichannel.session` (bad JSON, missing fields) must be treated as logged out, not crash the app — test in Task 3.
3. Logout when the server call fails (offline, already-revoked token → 401) must still clear local state and reach `/login` exactly once — test in Task 5.
4. A 401 from `GET /api/users/me` during sign-in (e.g. token revoked between login and fetch) must not leave a half-signed-in state — test in Task 4.
5. Refreshing `/login/verify` (no pending token in memory) must go to `/login`, not show a code form that can't succeed — test in Task 4.

---

### Task 1: Import the seed

**Files:** everything from `patternfly-react-seed` at commit `6fd01f5` except `.git/`.

- [ ] **Step 1:** `git clone https://github.com/patternfly/patternfly-react-seed <scratch>/seed && git -C <scratch>/seed checkout 6fd01f5`, then copy its contents (excluding `.git`) into the repo root.
- [ ] **Step 2:** `npm install` then `npm run ci-checks`. Expected: type-check, lint and tests pass on the pristine seed (record the seed's own test count).
- [ ] **Step 3: Commit** `chore: import patternfly-react-seed at 6fd01f5` (message body: upstream URL, commit, MIT licence kept in `LICENSE`).

### Task 2: Rebrand and shell

**Files:**
- Modify: `package.json` (`name: "omnichannel-crm-frontend"`, `description`, `repository`, drop `homepage`), `src/index.html` (title and application-name "Omnichannel CRM"), `README.md` (replace with short project README: what it is, `npm run start:dev` with `BACKEND_URL`, `npm run build`, packaging via backend `-Pwith-frontend`), `AGENTS.md` (repository layout section updated to the new folders).
- Modify: `webpack.dev.js` — `devServer.proxy: [{ context: ['/api', '/ws'], target: process.env.BACKEND_URL || 'http://localhost:8080', ws: true }]`.
- Modify: `src/app/AppLayout/AppLayout.tsx` — masthead brand is the text "Omnichannel CRM" (`MastheadLogo` with a `Title`/text, no PatternFly SVG); sidebar nav from routes.
- Modify: `src/app/routes.tsx` — routes: `/` Dashboard (label "Dashboard"), `/profile` Profile (label "Profile"), catch-all `path="*"` → `NotFound` (the seed's catch-all has no `path`; fix it).
- Create: `src/app/Profile/ProfilePage.tsx` (placeholder `Title` "Profile", filled in Task 6).
- Delete: `src/app/Support/`, `src/app/Settings/`, `src/app/bgimages/`, `src/app/__snapshots__/`.
- Create: `src/app/AppShell.tsx` — everything inside the router (later: `SessionProvider` + routes). `src/app/index.tsx` becomes `<BrowserRouter><AppShell/></BrowserRouter>`, so tests can render `AppShell` in a `MemoryRouter`.
- Create: `src/test/renderApp.tsx` — `renderApp(path = '/')` renders `<MemoryRouter initialEntries={[path]}><AppShell/></MemoryRouter>`, returns Testing Library result + `userEvent.setup()`.
- Replace: `src/app/app.test.tsx`.

**Interfaces:**
- Produces: `AppShell` (default export none; named export), `renderApp(path?: string)`, `routes` with labels `Dashboard`, `Profile`.

- [ ] **Step 1: Write `app.test.tsx`:**
  - `shows the app name in the masthead`: `renderApp('/')` → `screen.getByText('Omnichannel CRM')` visible.
  - `nav lists Dashboard and Profile only`: nav links named `Dashboard`, `Profile`; `queryByRole('link', {name: 'Support'})` null.
  - `unknown path shows not found`: `renderApp('/nope')` → text `404 Page not found`.
  - `profile route renders`: `renderApp('/profile')` → heading `Profile`.
- [ ] **Step 2: Run** `npx vitest run src/app/app.test.tsx` — Expected: FAIL (no `AppShell`/`renderApp`).
- [ ] **Step 3: Implement** the files above.
- [ ] **Step 4: Run** `npm run ci-checks` — Expected: PASS. Also `npm run build` — Expected: `dist/index.html` with title "Omnichannel CRM".
- [ ] **Step 5: Commit** `feat: rebrand seed as Omnichannel CRM with dashboard and profile routes`

### Task 3: Session store and API client

**Files:**
- Create: `src/app/session/sessionStore.ts`
- Create: `src/app/api/client.ts`, `src/app/api/auth.ts`, `src/app/api/users.ts`
- Create: `src/test/fetchMock.ts`
- Test: `src/app/session/sessionStore.test.ts`, `src/app/api/client.test.ts`, `src/app/api/auth.test.ts`

**Interfaces:**
- Produces:
  - `type StoredSession = { token: string; expiresAt: string }`
  - `readSession(now?: Date): StoredSession | null` — null when missing, unparsable, missing fields, or `expiresAt <= now`.
  - `writeSession(s: StoredSession): void`, `clearSession(): void`, `SESSION_KEY = 'omnichannel.session'`.
  - `class ApiError extends Error { status: number; code?: string; retryAfterSeconds?: number }` — `code` from body `error` or `code` field.
  - `apiFetch<T>(path: string, init?: { method?: string; body?: unknown; token?: string | null }): Promise<T>` — JSON in/out; 204 → `undefined`; non-2xx → throws `ApiError` (body parse failures tolerated).
  - `setUnauthorizedHandler(fn: (() => void) | null)` — called once per 401 from a non-`/api/auth/` path, before throwing.
  - `auth.ts`: `type LoginResult = { kind: 'signedIn'; token: string; expiresAt: string } | { kind: 'twoFactorRequired'; twoFactorToken: string }`; `login(username, password): Promise<LoginResult>`; `verifyOtp(twoFactorToken, otp): Promise<{token: string; expiresAt: string}>`; `logout(token): Promise<void>`.
  - `users.ts`: `type CurrentUser = { id; name; username; email: string | null; mobileNumber: string | null; role: 'SUPER_ADMIN' | 'APP_ADMIN' | 'ADMIN' | 'USER'; twoFactorEnabled: boolean }`; `getCurrentUser(token)`, `type TwoFactorSetup = { secret; qrCodeDataUri; issuer; username }`; `initiateTwoFactor(token)`, `confirmTwoFactor(token, secret, otp)`, `disableTwoFactor(token, otp)`.
  - `messages.ts` (in `api/`): `messageFor(error: unknown): string` mapping codes to the exact copy in Global Constraints (`too_many_attempts`/`TOO_MANY_ATTEMPTS` with minutes; `invalid_otp`/`INVALID_OTP`; unknown → "Something went wrong. Please try again.").
  - `fetchMock.ts`: `mockFetch(routes: Record<string, {status: number; body?: unknown} | ((req: {body: any; headers: Headers}) => {status: number; body?: unknown})>)` keyed `"METHOD /path"`; installs via `vi.stubGlobal('fetch', ...)`; returns the `vi.fn` for call assertions; unmatched → throws in test.

- [ ] **Step 1: Write tests.**
  - `sessionStore.test.ts`: round-trip write/read; `returns null for expired session` (expiresAt = now − 1s); `returns null for corrupt JSON` (`localStorage.setItem(SESSION_KEY, '{oops')`); `returns null for missing fields` (`'{"token":"x"}'`); `clearSession removes the key`; `survives localStorage throwing` (`vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error() })` → `readSession()` null, no throw).
  - `client.test.ts`: adds `Authorization: Bearer t` when token given; sends JSON body with `Content-Type: application/json`; 204 resolves `undefined`; 400 `{code:'INVALID_OTP'}` → `ApiError` status 400 code `INVALID_OTP`; 401 on `/api/users/me` calls the unauthorized handler once; 401 on `/api/auth/login` does **not** call it; 401 with empty body still throws `ApiError` status 401.
  - `auth.test.ts`: login → `{kind:'signedIn'}` for `{token, expiresAt}`; → `{kind:'twoFactorRequired'}` for `{twoFactorRequired:true, twoFactorToken}`; 429 → `ApiError` with `retryAfterSeconds` 1800 and `messageFor` = "Too many attempts. Try again in 30 minutes."; `messageFor` with retryAfterSeconds 45 → "... in 1 minute."; verifyOtp sends Bearer pending token and `{otp}`; logout POSTs with Bearer and resolves on 204.
- [ ] **Step 2: Run** `npx vitest run src/app/session src/app/api` — Expected: FAIL (modules missing).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm run ci-checks` — Expected: PASS.
- [ ] **Step 5: Commit** `feat: session store and API client`

### Task 4: Sign-in flow (SessionProvider, guard, login, code screen)

**Files:**
- Create: `src/app/session/SessionContext.tsx`, `src/app/session/RequireSession.tsx`
- Create: `src/app/Login/LoginPage.tsx`, `src/app/Login/VerifyCodePage.tsx`
- Modify: `src/app/AppShell.tsx` (wrap in `SessionProvider`; `/login` and `/login/verify` render outside `AppLayout`; everything else inside `RequireSession` + `AppLayout`)
- Test: `src/app/Login/login.test.tsx`

**Interfaces:**
- Consumes: Task 3 API and store.
- Produces: `useSession(): { status: 'loading' | 'signedOut' | 'signedIn'; user: CurrentUser | null; token: string | null; notice: string | null; pendingTwoFactorToken: string | null; startTwoFactor(t: string): void; signIn(token: string, expiresAt: string): Promise<void>; signOut(notice?: string): Promise<void>; refreshUser(): Promise<void> }`.
  - `signIn`: calls `getCurrentUser(token)`; role ≠ `SUPER_ADMIN` ⇒ `logout(token)` best-effort, clear, `status 'signedOut'`, `notice` = not-super-admin copy, and rejects; any error (incl. 401) ⇒ clear and `signedOut` with the error's message; success ⇒ `writeSession`, `status 'signedIn'`.
  - On mount: `readSession()`; present ⇒ `getCurrentUser` then `signedIn` (401 ⇒ expired notice); absent ⇒ `signedOut` (if a raw value existed but was expired ⇒ expired notice).
  - `RequireSession`: `loading` ⇒ PatternFly `Bullseye` + `Spinner`; `signedOut` ⇒ `<Navigate to="/login" state={{from: location.pathname}} replace/>`; else children.
  - LoginPage: PatternFly `LoginPage` + `LoginForm`, brand text "Omnichannel CRM", `loginTitle` "Log in to your account". Error shown via `LoginForm` `helperText` / `showHelperText` with `isValidUsername/isValidPassword` false. Shows `notice` (if any) as an inline `Alert` (info for logged-out notices, danger for errors). After success navigates to `state.from ?? '/'`. Already signed in ⇒ redirect to `/`.
  - VerifyCodePage: same `LoginPage` frame, `loginTitle` "Enter your verification code", one `TextInput` (`inputMode="numeric"`, `autoComplete="one-time-code"`, maxLength 6, label "6-digit code"), "Verify" button, "Back to log in" link. No pending token ⇒ `<Navigate to="/login" replace/>`. `invalid_token` ⇒ back to `/login` with the "took too long" notice.

- [ ] **Step 1: Write `login.test.tsx`** (use `renderApp`, `mockFetch`, clear `localStorage` in `beforeEach`):
  - `redirects to login when signed out`: `renderApp('/profile')` → heading "Log in to your account".
  - `signs in without two-factor`: login → `{token:'t1', expiresAt: +1h}`, me → SUPER_ADMIN `Nagesh Kumar`; type username/password, click "Log in" → dashboard heading `Dashboard`; `localStorage` `omnichannel.session` token `t1`.
  - `returns to the page that was asked for`: start at `/profile` → after login, heading `Profile`.
  - `signs in with a code`: login → `{twoFactorRequired:true, twoFactorToken:'p1'}` → URL `/login/verify`; type `123456`, Verify → verify-otp called with `Authorization: Bearer p1` and `{otp:'123456'}` → dashboard.
  - `wrong code stays on the code screen`: verify-otp 401 `{error:'invalid_otp'}` → text "That code is not valid. Try again."; still on code screen.
  - `expired pending token goes back to login`: verify-otp 401 `{error:'invalid_token'}` → login page with "Your sign-in took too long. Please log in again."
  - `shows invalid credentials`: 401 `{error:'invalid_credentials'}` → "Invalid username or password."
  - `shows lockout minutes`: 429 `{error:'too_many_attempts', retryAfterSeconds: 1800}` → "Too many attempts. Try again in 30 minutes."
  - `rejects non super admin`: me → role `USER` → stays on login with "This console is only available to the super admin."; logout endpoint called with that token; `localStorage` has no session.
  - `me 401 during sign-in leaves no session` (Review Focus 4): me → 401 → login page, error shown, no stored session, not on dashboard.
  - `verify page without pending token goes to login` (Review Focus 5): `renderApp('/login/verify')` → heading "Log in to your account".
  - `restores a stored session`: `localStorage` preset with valid session, me → SUPER_ADMIN → `renderApp('/')` shows dashboard without login.
  - `stored session rejected by server shows expired`: preset session, me → 401 → login with "Your session has expired. Please log in again."
- [ ] **Step 2: Run** `npx vitest run src/app/Login` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm run ci-checks` — Expected: PASS.
- [ ] **Step 5: Commit** `feat: super admin sign-in with optional two-factor code`

### Task 5: Masthead user menu, logout, expiry, cross-tab, dashboard

**Files:**
- Modify: `src/app/AppLayout/AppLayout.tsx` — `MastheadContent` with `Toolbar` → `Dropdown` toggle (`MenuToggle`, user icon + `user.name`); items: header text with name and role "Super admin", "Profile" (navigates `/profile`), divider, "Log out".
- Modify: `src/app/session/SessionContext.tsx` — `signOut`: call `logout(token)` best-effort (ignore any error), then `clearSession()`, `status 'signedOut'`, `notice`. Register `setUnauthorizedHandler(...)`: only while `status === 'signedIn'`, sign out locally (no server call) with the expired copy; during `signIn`/restore the 401 is handled by those flows (Task 4 tests must stay green). Timer: when signed in, `setTimeout` to `expiresAt` ⇒ sign out with expired notice (no server call). `window` `storage` event on `SESSION_KEY` with `newValue === null` ⇒ local sign-out with "You were logged out in another tab."
- Modify: `src/app/Dashboard/Dashboard.tsx` — `PageSection` with `Title` h1 "Dashboard" and `EmptyState` (`titleText` "Welcome, <name>", body "This is where your channels and leads will appear."). Document title "Dashboard | Omnichannel CRM".
- Test: `src/app/session/session.test.tsx`

**Interfaces:**
- Consumes: Task 4 `useSession`.

- [ ] **Step 1: Write `session.test.tsx`** (signed-in fixture helper: preset `localStorage` + me SUPER_ADMIN `Nagesh Kumar`):
  - `dashboard welcomes the user`: "Welcome, Nagesh Kumar".
  - `user menu shows name and logs out`: open toggle "Nagesh Kumar", click "Log out" → `POST /api/auth/logout` with `Bearer <token>`; login page; `localStorage` session gone.
  - `logout still signs out when server fails` (Review Focus 3): logout route returns 500 (and separately 401) → login page, session cleared, logout fetch called exactly once.
  - `profile item navigates`: menu → "Profile" → heading `Profile`.
  - `401 from an API call signs out`: on profile, `GET /api/users/me` refetch (call `refreshUser`) → 401 → login with expired copy; no logout POST issued.
  - `expires at expiresAt`: `vi.useFakeTimers({ shouldAdvanceTime: true })`, session expiring in 60 s → advance 61 s → login with expired copy.
  - `already-expired stored session` (Review Focus 1): preset `expiresAt` = now − 1 min → `renderApp('/')` → login with expired copy; me never fetched.
  - `logout in another tab`: dispatch `new StorageEvent('storage', { key: 'omnichannel.session', newValue: null })` → login with "You were logged out in another tab."
- [ ] **Step 2: Run** `npx vitest run src/app/session/session.test.tsx` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm run ci-checks` — Expected: PASS.
- [ ] **Step 5: Commit** `feat: user menu, server-side logout, expiry and cross-tab sign-out`

### Task 6: Profile page and two-factor

**Files:**
- Modify: `src/app/Profile/ProfilePage.tsx`
- Create: `src/app/Profile/TwoFactorCard.tsx`, `src/app/Profile/EnableTwoFactorPanel.tsx`, `src/app/Profile/DisableTwoFactorModal.tsx`
- Test: `src/app/Profile/profile.test.tsx`

**Interfaces:**
- Consumes: `useSession()` (`user`, `token`, `refreshUser`), `initiateTwoFactor`, `confirmTwoFactor`, `disableTwoFactor`, `messageFor`.
- Content (exact copy where quoted):
  - Page `Title` h1 "Profile"; document title "Profile | Omnichannel CRM".
  - Details `Card` "Your details": `DescriptionList` Name, Username, Email, Mobile, Role ("Super admin"); null shown as "—".
  - `TwoFactorCard` "Two-factor authentication": `Label` "Off" (grey) or "On" (green).
    - Off: body "Protect your account with a 6-digit code from an authenticator app on your phone." + primary "Enable two-factor" button → `EnableTwoFactorPanel`.
    - On: secondary-danger "Disable two-factor" button → `DisableTwoFactorModal` (`Modal` variant small, title "Disable two-factor authentication", 6-digit field, "Disable" danger + "Cancel").
  - `EnableTwoFactorPanel` calls `initiate` on open (spinner while loading), then an ordered `List` (`component="ol"`) of three steps:
    1. "Install an authenticator app" — "Google Authenticator" (links "App Store" → `https://apps.apple.com/app/google-authenticator/id388497605`, "Google Play" → `https://play.google.com/store/apps/details?id=com.google.android.apps.authenticator2`) or "Microsoft Authenticator" ("App Store" → `https://apps.apple.com/app/microsoft-authenticator/id983156458`, "Google Play" → `https://play.google.com/store/apps/details?id=com.azure.authenticator`). Links open in a new tab (`target="_blank" rel="noopener noreferrer"`).
    2. "Add your account" — "Open the app, tap +, choose Scan a QR code, and scan this code." `<img alt="QR code for two-factor setup" src={qrCodeDataUri} width=200>`. `ExpandableSection` toggle "Can't scan the code?": "Choose Enter a setup key, select Time based, and enter:" Account `username`, Issuer `issuer`, Key in `ClipboardCopy isReadOnly` showing the secret grouped in fours (`ABCD EFGH ...`; copy value without spaces).
    3. "Enter the 6-digit code" — field + primary "Turn on" + link "Cancel".
  - Success: `confirm` → `refreshUser()` → panel closes, success `Alert` "Two-factor authentication is on." Disable success: "Two-factor authentication is off."
  - Errors inline (`Alert` danger, inline) via `messageFor` (wrong code, lockout). A 409 `TWO_FACTOR_STATE` ⇒ `refreshUser()` and close the panel/modal.

- [ ] **Step 1: Write `profile.test.tsx`** (signed-in fixture; me returns `twoFactorEnabled` false unless stated):
  - `shows details`: "Nagesh Kumar", "nageshk", email, mobile, "Super admin"; null mobile shows "—".
  - `shows two-factor off`: label "Off", button "Enable two-factor".
  - `enable shows install help and QR`: initiate → `{secret:'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', qrCodeDataUri:'data:image/png;base64,AAA', issuer:'Omnichannel CRM', username:'nageshk'}`; click Enable → link "App Store" hrefs include both Apple URLs, "Google Play" both Play URLs; img alt "QR code for two-factor setup" src `data:image/png;base64,AAA`; expand "Can't scan the code?" → text `JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP`, "Omnichannel CRM", "nageshk".
  - `wrong code shows error`: confirm → 400 `{code:'INVALID_OTP'}` → "That code is not valid. Try again."; still signed in (no redirect to login).
  - `confirm turns two-factor on`: confirm → 204 with body `{secret, otp:'123456'}`; next me → `twoFactorEnabled:true` → label "On", alert "Two-factor authentication is on.", "Disable two-factor" visible.
  - `cancel closes the panel without saving`: no confirm call made; "Enable two-factor" visible again.
  - `lockout message`: confirm → 429 `{code:'TOO_MANY_ATTEMPTS', retryAfterSeconds:600}` → "Too many attempts. Try again in 10 minutes."
  - `disable asks for a code and turns off`: me `twoFactorEnabled:true`; Disable → modal → code → disable 204 with `{otp}`; me → false → label "Off", alert "Two-factor authentication is off."
  - `disable wrong code keeps modal open`: 400 → error in modal, modal still open.
- [ ] **Step 2: Run** `npx vitest run src/app/Profile` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm run ci-checks` — Expected: PASS.
- [ ] **Step 5: Commit** `feat: profile page with two-factor setup help`

### Task 7: End-to-end against the backend (manual, no code unless a defect)

- [ ] `npm run build` → `dist/index.html`. In `whatsapp-backend`: `./mvnw clean -Pwith-frontend -DskipTests package`; run the jar on a free port (8080 may be taken; use 8089) with the local Mongo users (`nageshk / changeme-now`, two-factor off).
- [ ] Walk through in a browser or with `curl` + a headless check where a browser isn't available: `/` → login → dashboard "Welcome, Nagesh Kumar" → Profile → enable two-factor with a code generated from the shown secret (`oathtool --totp -b <secret>` if installed, else a small `node` TOTP one-liner) → log out → log in → code screen → dashboard → refresh `/profile` (deep link served) → disable two-factor → log out; replay the old token with `curl /api/users/me` → 401.
- [ ] Dev proxy check: `BACKEND_URL=http://localhost:8089 npm run start:dev`, `curl -s localhost:9000/api/users/me` → 401 from the backend (proxy works).
- [ ] Leave local Mongo with two-factor **off** for nageshk afterwards. Stop servers. Any defect found → failing test + fix in the owning area, commit `fix: ...`.
