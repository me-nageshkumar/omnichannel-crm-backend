# Link addresses: backend implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task
> (the user's choice: inline, on Opus). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A super admin gives a brand its own link address; message and form links use it once a check proves it
reaches this server; the platform address is editable without a restart.

**Architecture:** One service, `PagesAddresses`, answers "which address does this brand's link use" and "is this
host ours". Each page and form stores the address it was built with and renders from it. A probe fetches a nonce
through the address to prove it reaches us. A servlet filter keeps a customer's host to the public pages, and a
loopback-only endpoint tells the edge (Caddy) which hosts may get a certificate.

**Tech stack:** Java 21, Spring Boot 4.1, Jackson 3, MongoDB standalone, embedded Mongo + `MutableClock` + MockMvc
in tests.

**Spec:** `docs/superpowers/specs/2026-11-18-link-addresses-design.md` (read it first; § numbers below are its).
Console plan: `docs/superpowers/plans/2026-11-18-link-addresses-console.md`.

## Global constraints

- Read `docs/superpowers/project-rules.md` §1 and §2 before any git or build command. Work only in this worktree
  (`.worktrees/link-addresses`, branch `link-addresses`). No merge, push or deploy: the user asks for those.
- One Maven build at a time. While working run only the named tests:
  `./mvnw -q -o -pl omnichannel-app -am test -Dtest='<Names>' -Dsurefire.failIfNoSpecifiedTests=false`.
  The full `./mvnw -q -o verify` runs once, in Task 7. After any run `pgrep -f '/.embedmongo/' | wc -l` must be 0.
- No transactions: guarded single-document updates; `@Version` only on what a screen saves.
- Collections camelCase. "Template" means Meta templates only. Never send a real message. Never print or log the
  secrets key.
- `appConfig.pagesBaseUrl` and `OMNICHANNEL_PAGES_BASE_URL` keep working as the startup value; `AppConfigLoader`
  is not changed.
- New settings are read with `@Value` (not new `OmnichannelProperties` components: five unit tests build that
  record by hand): `omnichannel.pages.allow-insecure` (default `false`), `omnichannel.pages.ask-from` (default
  empty), `omnichannel.pages.trust-forwarded-host` (default `false`), `omnichannel.pages.check-timeout` (`20s`).
  The test profile sets `allow-insecure: true`.
- Model split: you are Opus. Dispatch the final whole-branch reviewer with `model: "fable"`. Stop and say
  "Switch to Fable: `/model fable`" when (1) the way forward is a design decision or spec and plan conflict on what
  users get, (2) review findings need a design change, (3) the user asks for a spec, plan or design check, (4) the
  same failure survived two real debugging attempts. Ordinary plan gaps are rulings in the ledger.
- Commit trailer: as the session's reminder says (`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` on Opus).

Paths are relative to the module's `src/main/java/me/nageshk/omnichannel/` (tests:
`omnichannel-app/src/test/java/me/nageshk/omnichannel/app/`). Existing ITs to copy set-up from:
`inbox/MessagePageIT` (creating and serving a message page), `chatbot/ChatbotFormIT` (a form page),
`chatbot/ChatbotSettingsApiIT` (a platform singleton's API with a super admin), `billing/FeatureGroupSeederIT`.

## Review focus

1. What a person types: upper case, a trailing slash, spaces, `http://`, a port, an IP, a path with a dot or four
   segments, an international name. Each is either normalised or refused with a field message, never stored
   half-right. (Task 1)
2. A domain whose DNS points somewhere else and answers `200` with other content, or redirects to another site:
   not working. (Task 3)
3. Two people act at once: a check finishing after the address was changed must not mark the new address with the
   old result. (Task 3)
4. A stranger sends `X-Forwarded-Host: <customer host>` or asks `host-allowed` through the public address: no
   change in what is served, and no answer. (Task 5)
5. The platform address is saved as, or already equals, a host a brand uses; a brand's workspace is disabled or
   deleted: links still resolve to a working address and `hostKnown` stays right. (Task 2)

---

### Task 1: The address value, the stored documents and the permission

**Files:**
- Create: `omnichannel-service` `service/page/PagesUrl.java`
- Create: `omnichannel-db` `db/page/PagesAddress.java`, `PagesAddressRepository.java`, `PagesAddressStatus.java`,
  `PagesSettings.java`, `PagesSettingsRepository.java`
- Modify: `omnichannel-db` `db/page/MessagePage.java`, `db/chatbot/ChatbotForm.java` (add `String baseUrl`);
  `omnichannel-security` `security/access/Authority.java`
- Test: create `omnichannel-service/src/test/java/.../service/page/PagesUrlTest.java`; `AuthorityTest`,
  `access/RoleSeederIT` (pins 60 → 61)

**Interfaces — produces:**
- `record PagesUrl(String baseUrl, String host, String path)` with
  `static PagesUrl parse(String text, boolean allowInsecure)` throwing `ValidationException("baseUrl", message)`.
  `baseUrl` is the normalised form: scheme and host lower-case, no trailing slash, `path` `""` or `/a/b`.
- `PagesAddress` (`@Document("pagesAddresses")`): `id, workspaceId (@Indexed(unique = true)), customerId, baseUrl,
  host (@Indexed), PagesAddressStatus status, Instant checkedAt, String lastError, int failures, String checkNonce,
  Instant setAt, String setBy, List<FormerHost> formerHosts, @Version Long version`;
  `record FormerHost(String host, Instant until)`; a plain index on `formerHosts.host`.
  `enum PagesAddressStatus { UNCHECKED, WORKING, FAILING }`.
- `PagesSettings` (`@Document("pagesSettings")`, `ID = "pages"`): `baseUrl, List<FormerHost> formerHosts,
  updatedAt, updatedBy, @Version`.
- `Authority.PAGES_ADDRESS_MANAGE("pages-address:manage", RoleLevel.PLATFORM, "Set the link addresses of the
  platform and of brands")`, no roles, declared after `CHATBOT_DEFAULTS_MANAGE`.

- [ ] **Step 1: failing tests.** `PagesUrlTest` (Review focus 1), one test each:
  `"https://User-Task.BimalInstitute.com/"` → baseUrl `https://user-task.bimalinstitute.com`, path `""`;
  `" https://bimalinstitute.com/wa/ "` → `https://bimalinstitute.com/wa`, host `bimalinstitute.com`, path `/wa`;
  refused with these messages: no scheme or `ftp://` → "Start the address with https://"; `http://x.com` with
  `allowInsecure` false → "Use https://"; an IPv4 or `[::1]` host → "Use a domain name, not an IP address";
  `https://x.com:8443` (insecure off) → "Leave out the port"; a query, fragment or `user@` → "Only a domain and an
  optional folder, e.g. https://example.com/wa"; a segment with `.` or a space, or four segments → "The folder may
  have up to 3 parts of letters, digits, - and _"; a host with a non-ASCII letter → "Use the ASCII (xn--) form of
  the name"; a host without a dot (insecure off) → "Use a full domain name"; blank → "Enter an address". With
  `allowInsecure` true: `http://localhost:8089/a` parses (host `localhost`, port kept in `baseUrl`).
  `AuthorityTest` and `RoleSeederIT`: 60 → 61; `RoleSeederIT`: no built-in role holds `pages-address:manage`.
- [ ] **Step 2: run, expect FAIL** (compile errors count).
- [ ] **Step 3: implement.**
- [ ] **Step 4: run, expect PASS**; also `FeatureGroupSeederIT` (a PLATFORM authority must not enter a group).
- [ ] **Step 5: commit** `feat(pages): the link address value, its documents and pages-address:manage`.

### Task 2: `PagesAddresses`: which address a link uses, and setting one

**Files:**
- Create: `omnichannel-service` `service/page/PagesAddresses.java`, `service/page/PagesAddressViews.java`
- Test: create `page/PagesAddressesIT.java`

**Interfaces — consumes:** Task 1. **Produces** (`@Service PagesAddresses`):
- `String platform()` — the saved `PagesSettings.baseUrl`, else `props.pages().baseUrl()` (trailing slash removed).
- `String platformHost()`.
- `String forWorkspace(String workspaceId)` — the brand's `baseUrl` when `status == WORKING`, else `platform()`.
- `boolean hostKnown(String host)` — platform host (current or former in time), a brand's current host of any
  status, or a former host with `until` after now. Case-insensitive; null/blank → false.
- `boolean customerHost(String host)` — a brand's current or in-time former host that is not the platform host.
- `PagesAddress set(String customerId, String workspaceId, String text, Long version, String byUserId)` —
  the workspace must belong to the customer (else `NotFoundException`); `PagesUrl.parse`; refuses the platform
  host (`ValidationException("baseUrl", "This is the platform's own address")`) and a host another customer's brand
  uses now or as an in-time former host (`CodedException` 409 `PAGES_HOST_TAKEN`, "Another customer uses this
  domain"); a different previous host moves to `formerHosts` with `until = now + ttlDays`; former entries past
  `until` are dropped; a new host that is in the brand's own `formerHosts` is taken out of them; status
  `UNCHECKED`, `failures 0`, `checkedAt/lastError/checkNonce` null; stale `version` → the project's usual 409.
  Task 3 makes it run the check.
- `PagesAddress remove(String customerId, String workspaceId, Long version, String byUserId)` — clears
  `baseUrl/host/status` fields, moves the host to `formerHosts`.
- `PlatformView savePlatform(String textOrNull, Long version, String byUserId)` — null removes the saved address
  (startup value in use again); a changed host moves the old one to `PagesSettings.formerHosts`; refuses a host a
  brand uses now (`ValidationException("baseUrl", "A brand uses this domain")`). `PlatformView platformView()`.
- `PagesAddressViews`: `record AddressView(String baseUrl, PagesAddressStatus status, Instant checkedAt,
  String lastError, boolean inUse)`, `record FormerHostView(String host, Instant until)`,
  `record BrandRow(String workspaceId, String workspaceName, AddressView address, List<FormerHostView> formerHosts,
  Long version)`, `record CustomerView(String platformBaseUrl, List<BrandRow> brands)`,
  `record PlatformView(String baseUrl, String source, String startupBaseUrl, Long version, String warning)`
  (`source` `"SAVED"` or `"STARTUP"`). `CustomerView forCustomer(String customerId)` lists every workspace of the
  customer by name, disabled ones included.
- Lookups are read from Mongo per call (no cache): they run once per send and per page view.

- [ ] **Step 1: failing tests** in `PagesAddressesIT`: `noAddressUsesThePlatform`; `uncheckedAndFailingUseThePlatform`
  and `workingUsesTheBrands` (set the status directly in Mongo); `setNormalisesAndStartsUnchecked`;
  `setRefusesThePlatformHost`; `setRefusesAnotherCustomersHost` (current) and `...FormerHost` (in time; allowed
  after `until`); `brandsOfOneCustomerMayShareAnAddress`; `changingKeepsTheOldHostKnownForTheTtl` (known at +89
  days, unknown at +91; pruned from the document on the next `set`); `settingAFormerHostAgainTakesItOutOfFormer`;
  `removeKeepsTheHostKnown`; `aStaleVersionIs409`; `aWorkspaceOfAnotherCustomerIsNotFound`;
  `savedPlatformWinsAndNullRestoresStartup`; `platformChangeKeepsItsOldHostKnown`;
  `platformCannotTakeABrandsHost` (Review focus 5); `aDisabledBrandKeepsItsAddressAndHostKnown` (Review focus 5);
  `hostKnownIsCaseInsensitive`; `customerHostIsNeverThePlatformHost`.
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.**
- [ ] **Step 4: run, expect PASS.**
- [ ] **Step 5: commit** `feat(pages): PagesAddresses decides a brand's link address and keeps former hosts`.

### Task 3: The check

**Files:**
- Create: `omnichannel-service` `service/page/PagesProbe.java` (interface), `HttpPagesProbe.java`,
  `PagesAddressChecker.java`, `PagesAddressSweeper.java`; `omnichannel-app` `app/page/PagesCheckController.java`
- Modify: `service/page/PagesAddresses.java` (`set` and `savePlatform` run the check)
- Test: create `page/PagesAddressCheckIT.java`

**Interfaces — produces:**
- `interface PagesProbe { Result get(String url); record Result(int status, String body, String failure) {} }` —
  `failure` is one of `NO_ANSWER`, `BAD_CERTIFICATE`, `NO_SUCH_NAME`, `REFUSED_ADDRESS`, or null.
  `HttpPagesProbe`: the JDK client behind `RestClient` (as `ChatbotHttpClient`), timeout
  `omnichannel.pages.check-timeout`, redirects **not** followed, at most 1 KB of body read, its own
  `new HttpAddressGuard(allowInsecure)`.
- `PagesCheckController`: `GET /t/-/check/{nonce}` → `200 text/plain` body `"omnichannel-pages " + nonce` when a
  `PagesAddress` has `checkNonce == nonce` or the platform check holds it (an in-memory set with a one-minute life
  is enough for the platform's own check); else `404`. Mapped so `/t/{token}` does not swallow it.
- `PagesAddressChecker`:
  - `PagesAddress check(String workspaceId)` — writes a fresh nonce (32 random URL-safe chars) with a guarded
    update on `{workspaceId, baseUrl: <the one read>}`; probes `baseUrl + "/t/-/check/" + nonce`; then one guarded
    `$set` on `{workspaceId, checkNonce: nonce}` (so a result for an address that changed meanwhile is dropped,
    Review focus 3): `WORKING, failures 0, lastError null` on `200` with exactly the expected body; else
    `FAILING` with `lastError`: `NO_ANSWER` → "The address did not answer"; `BAD_CERTIFICATE` → "The certificate is
    not valid"; `NO_SUCH_NAME` → "The name does not exist"; `REFUSED_ADDRESS` → "The address points to a private
    network"; any other answer (wrong body, a redirect, another status) → "The address answered, but not from this
    server". Sets `checkedAt`. Does not touch `version`.
  - `String checkPlatform(String baseUrl)` — the same request; returns the failure text or null.
  - `int recheckAll()` — every address with a `baseUrl`: success → `WORKING, failures 0`; failure →
    `failures + 1`, and `FAILING` only when that reaches 3 (an `UNCHECKED` one becomes `FAILING` at once).
- `PagesAddresses.set` returns the address after `check`; `savePlatform` puts `checkPlatform`'s text in
  `PlatformView.warning`.
- `PagesAddressSweeper`: `@Scheduled(cron = "0 15 4 * * *")` calls `recheckAll()` (as `MessagePageSweeper`).

- [ ] **Step 1: failing tests** in `PagesAddressCheckIT` (random-port server; `http://localhost:<port>` is the
  address that works; a stub `PagesProbe` via `@MockitoSpyBean` where a failure is needed):
  `aWorkingAddressIsMarkedWorking` (real round trip through the test server);
  `aFolderNobodyStripsIsNotWorking` (`http://localhost:<port>/wa` → "answered, but not from this server");
  `anotherSitesAnswerIsNotWorking` (probe returns `200` with other text; Review focus 2);
  `aRedirectIsNotFollowed` (probe returns `302`); `noAnswerBadCertificateAndUnknownNameSayWhy` (three texts);
  `theNonceOfAnotherBrandDoesNotPass` (the endpoint answers only the stored nonce; an old nonce → 404);
  `aResultForAnAddressThatChangedIsDropped` (inside the probe stub, call `set` with another address; the first
  check's result must not land; Review focus 3); `settingAnAddressChecksIt`; `aManualFailureMarksFailingAtOnce`;
  `theDailyPassNeedsThreeFailuresInARow` (two failures keep `WORKING` and links on the brand's address; the third
  → `FAILING` and `forWorkspace` → platform; one success resets); `theDailyPassRecoversAFailingAddress`;
  `savingThePlatformAddressWarnsWhenItDoesNotAnswer`.
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.**
- [ ] **Step 4: run, expect PASS**; also `MessagePageIT` (the `/t/-/check` mapping must not disturb `/t/{token}`).
- [ ] **Step 5: commit** `feat(pages): a check proves a link address reaches this server; daily re-check`.

### Task 4: Links and pages use the stored address

**Files:**
- Modify: `service/page/MessagePageService.java`, `MessagePageRenderer.java` (only if it reads the base anywhere
  but `PageModel.baseUrl()`), `service/chatbot/form/FormPageService.java`,
  `service/chatbot/ChatbotPreviewRenderer.java`, `omnichannel-app` `app/cloud/CloudMediaController.java`
- Create: `service/page/PagesBaseUrlBackfill.java`
- Test: `inbox/MessagePageIT`, `chatbot/ChatbotFormIT`; create `page/PagesBaseUrlBackfillIT.java`

**Interfaces — consumes:** `PagesAddresses.forWorkspace`, `platform()`. **Produces:**
- `MessagePageService.create` stores `baseUrl = addresses.forWorkspace(workspaceId)`; `link(page)` and `html(page)`
  use `page.getBaseUrl()`, falling back to `addresses.platform()` when null; `previewHtml(workspaceId, …)` uses
  `forWorkspace(workspaceId)`.
- `FormPageService.create` stores `baseUrl` the same way; `url(form)` uses it; every `renderer.form(...)` call
  passes the action `url(form)` (absolute), not `"/f/" + token`.
- `CloudMediaController` builds its URL from `addresses.platform()`.
- No other reader of `props.pages().baseUrl()` remains except `PagesAddresses`.
- `PagesBaseUrlBackfill` (`ApplicationRunner`, once, marker `{_id: "pagesBaseUrl"}` in `dataMigrations`):
  `updateMulti` on `MessagePage` and `ChatbotForm` where `baseUrl` is missing or null → `platform()`.

- [ ] **Step 1: failing tests.** `MessagePageIT`: `aBrandWithAWorkingAddressSendsLinksUnderIt` (link starts with
  the brand's base, folder included; the HTML's `og:image`, `og:url`, beacon, media and action URLs all start
  with it); `withoutAWorkingAddressLinksUseThePlatform` (none, `UNCHECKED`, `FAILING`);
  `aPageKeepsItsAddressAfterTheBrandChangesIt`; `aRowWithoutABaseUsesThePlatform`;
  `thePreviewUsesTheBrandsAddress`. `ChatbotFormIT`: `aFormLinkAndItsActionUseTheBrandsAddress` (the `<form
  action>` is the absolute URL, on first render and after an invalid submit); `aFormKeepsItsAddress`.
  `PagesBaseUrlBackfillIT`: `oldRowsGetThePlatformAddressOnce` (run twice; a row given a base in between keeps it).
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.**
- [ ] **Step 4: run, expect PASS**; then `-Dtest='MessagePage*IT,ChatbotFormIT,CloudSendApiIT,CloudMediaIT,
  ChatbotApiIT,InboxSendIT,Campaign*IT'`.
- [ ] **Step 5: commit** `feat(pages): links and pages use the address they were built with`.

### Task 5: A customer's host serves pages only; the edge asks which hosts are ours

**Files:**
- Create: `omnichannel-app` `app/page/PagesHostFilter.java` (registered with the highest precedence, before
  security), `app/page/PagesHostController.java`
- Test: create `page/PagesHostIT.java`

**Interfaces — consumes:** `PagesAddresses.customerHost`, `hostKnown`. **Produces:**
- `PagesHostFilter`: host = the `Host` header without its port, or the first value of `X-Forwarded-Host` when
  `omnichannel.pages.trust-forwarded-host` is true. When `customerHost(host)`: only `GET` and `POST` whose path
  starts with `/t/` or `/f/` go on; anything else → `404`, empty body. Every other host: untouched.
- `PagesHostController`: `GET /internal/pages/host-allowed?domain=` → `200` (empty) when `hostKnown(domain)`,
  else `404`. `404` too when the request has `X-Forwarded-For`, `Forwarded` or `Via`, or its remote address is
  neither loopback nor listed in `omnichannel.pages.ask-from` (comma-separated).

- [ ] **Step 1: failing tests** in `PagesHostIT` (MockMvc; `Host` set with `.header("Host", …)`; remote address
  with a request post-processor): `onACustomerHostPagesAreServed` (`/t/<token>` and `/f/<token>` → as on the
  platform host); `onACustomerHostTheConsoleAndApiAreNotFound` (`/`, `/login`, `/api/users/me` with a valid
  bearer, `/ws`, `/internal/pages/host-allowed` → 404, empty body); `aFormerHostInTimeBehavesAsACustomerHost`;
  `onThePlatformHostAndAnUnknownHostNothingChanges` (`/login` → 200); `aForwardedHostHeaderIsIgnoredByDefault`
  (Review focus 4) and `...IsUsedWhenTrusted` (a nested test class with the property on);
  `hostAllowedSaysYesForKnownHosts` (platform, a brand's `UNCHECKED` host, a former host in time) and
  `...NoForUnknownAndExpired`; `hostAllowedDoesNotAnswerAProxiedOrRemoteCaller` (with `X-Forwarded-For`; with
  remote address `203.0.113.5`; Review focus 4); `hostAllowedAnswersAnAskFromAddress`.
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.**
- [ ] **Step 4: run, expect PASS**; also `MessagePageIT,ChatbotFormIT,InboxApiIT` (nothing else lost its route).
- [ ] **Step 5: commit** `feat(pages): customer hosts serve pages only; host-allowed for the edge`.

### Task 6: The API

**Files:**
- Create: `omnichannel-app` `app/page/PagesAddressController.java`
- Test: create `page/PagesAddressApiIT.java`

**Interfaces — produces** (all `@PreAuthorize("hasAuthority('pages-address:manage')")`; request records in the
controller):
- `GET /api/platform/pages-address` → `PlatformView`; `PUT` `{baseUrl, version}` (`baseUrl` null removes) →
  `PlatformView` with `warning`.
- `GET /api/customers/{id}/pages-addresses` → `CustomerView`.
- `PUT /api/customers/{id}/workspaces/{workspaceId}/pages-address` `{baseUrl, version}` → `BrandRow`
  (`baseUrl` null → `remove`); `POST …/pages-address/check` → `BrandRow` (404 `NO_PAGES_ADDRESS` when the brand has
  none).
- `AddressView.inUse` = status `WORKING`.

- [ ] **Step 1: failing tests** in `PagesAddressApiIT`: `everyEndpointNeedsThePermission` (an account manager with
  `customer:manage` → 403 on all five; the super admin → 2xx); `listsEveryBrandWithItsAddressAndThePlatformBase`;
  `settingReturnsTheCheckedRow` (`status: "WORKING"`, `inUse: true` against `http://localhost:<port>`);
  `aBadAddressIsAFieldError` (400, `fields.baseUrl`); `anotherCustomersDomainIs409` (`code: "PAGES_HOST_TAKEN"`);
  `aStaleVersionIs409`; `removingLeavesTheFormerHostWithItsEndDate`; `checkOnABrandWithoutAnAddressIs404`;
  `platformGetSaysWhereItComesFrom` (`source: "STARTUP"` first, `"SAVED"` after a PUT, `"STARTUP"` again after
  `baseUrl: null`); `platformPutWarnsButSaves`.
- [ ] **Step 2: run, expect FAIL.**
- [ ] **Step 3: implement.**
- [ ] **Step 4: run, expect PASS.**
- [ ] **Step 5: commit** `feat(pages): API for the platform's and brands' link addresses`.

### Task 7: The edge's configuration, the docs, full run

**Files:**
- Create: `docs/deploy/Caddyfile`
- Modify: `README.md`, `docs/superpowers/project-rules.md` (§2), `docs/superpowers/known-limits.md`,
  `omnichannel-app/src/main/resources/application.yml` (the four `omnichannel.pages.*` settings, commented)

- [ ] **Step 1: `docs/deploy/Caddyfile`:** a global `on_demand_tls { ask http://localhost:8080/internal/pages/host-allowed }`
  and one `https://` site with `tls { on_demand }` and `reverse_proxy localhost:8080`, with comments: every known
  host (the platform's included) gets its certificate on first request; unknown hosts get none; Caddy must be the
  only thing listening on 443.
- [ ] **Step 2: docs.** README, a section "Link addresses": what an address is; `pagesAddresses` and
  `pagesSettings`; which address a link uses; the check and the daily pass; former hosts and the 90 days; the
  filter; `host-allowed`; the five endpoints; the settings; what the customer does (a CNAME record to the platform
  host for a subdomain; forwarding of `<folder>/t/` and `<folder>/f/` to the platform address with the folder
  removed for a bare domain). `project-rules.md` §2: the startup value still comes from the environment or
  `appConfig`, a saved platform address wins until "use the startup setting"; 8089 saves none. `known-limits.md`:
  the four risks of spec §9.
- [ ] **Step 3: full run, alone:** `./mvnw -q -o verify > <workspace>/verify.log 2>&1; echo exit=$?; tail -40
  <workspace>/verify.log`. Expected: `exit=0`. Then `pgrep -f '/.embedmongo/' | wc -l` → `0`.
- [ ] **Step 4: commit** `docs: link addresses; Caddyfile for certificates on demand`.

## Contract for the console

- Authority code `pages-address:manage` (PLATFORM; only the super admin has it).
- `GET /api/platform/pages-address` → `{baseUrl, source: "SAVED" | "STARTUP", startupBaseUrl, version, warning}`;
  `PUT` body `{baseUrl: string | null, version}` → the same; `warning` is the failed check's text or null.
- `GET /api/customers/{id}/pages-addresses` → `{platformBaseUrl, brands: [{workspaceId, workspaceName,
  address: {baseUrl, status: "UNCHECKED" | "WORKING" | "FAILING", checkedAt, lastError, inUse} | null,
  formerHosts: [{host, until}], version}]}`.
- `PUT /api/customers/{id}/workspaces/{workspaceId}/pages-address` body `{baseUrl: string | null, version}` → one
  brand row; 400 `{fields: {baseUrl}}`; 409 `PAGES_HOST_TAKEN`; 409 on a stale version.
- `POST /api/customers/{id}/workspaces/{workspaceId}/pages-address/check` → one brand row; 404 `NO_PAGES_ADDRESS`.
