# Link addresses: a brand's own domain on message and form links

Design, 2026-11-18. Branch `link-addresses` in the backend and console repos. Plans follow via writing-plans; the
user executes them with Opus.

## 1. Intent

A message with buttons, a list or a form goes out as a link with a preview card; the link opens a web page of ours.
Today every link starts with one address for the whole installation (`omnichannel.pages.base-url`).

The user wants a person who enquires with Bimal Institute to land on a Bimal Institute link: it feels right, and a
link under a stranger's domain does not. So a super admin sets the address a business's links use
(`https://user-task.bimalinstitute.com` or `https://bimalinstitute.com/...`), and the customer points their DNS or
their proxy at our server.

## 2. Goals, non-goals, anti-goals

Goals: an address per brand, set by a super admin; a check that it really reaches this installation before any link
uses it; links, preview cards and everything the page loads under that address; the platform's own address editable
on a screen without a restart; automatic HTTPS certificates for customer subdomains, with nothing manual per
customer on our side.

Non-goals (v1): the customer setting or seeing the address themselves; a domain for the console or the API; email or
other channels; AWS-managed certificates; checking the customer's DNS records for them; serving a customer's bare
domain by DNS (a bare domain is forwarded by the customer's proxy, §4 A).

Anti-goals (a failure even if it works):
- a message sent with a link that does not open, because an address was used before it worked or after it broke;
- links already sent that stop opening when a brand's address changes;
- the console's login, or any API, reachable under a customer's domain;
- our server asking for a certificate for any host name a stranger points at it;
- a brand's links under another customer's domain.

## 3. How it works today (facts the design rests on)

- `omnichannel.pages.base-url` (`OmnichannelProperties.Pages.baseUrl`) is read at startup from the environment
  (`OMNICHANNEL_PAGES_BASE_URL`), else `appConfig.pagesBaseUrl` (`AppConfigLoader`), else `http://localhost:8080`.
- Message pages: `MessagePageController` serves `GET /t/{token}`, `/t/{token}/og.png`, `/t/{token}/m` (media),
  `/t/{token}/a`, and `POST /t/{token}/e` (the page's beacon). `MessagePageService.link(page)` builds
  `baseUrl + "/t/" + token`; `MessagePageRenderer` builds `og:image`, `og:url` and the beacon as absolute URLs from
  the same base. `MessagePage` carries `workspaceId` and `customerId`.
- Form pages: `FormPageController` serves `GET /f/{token}`, `/f/{token}/og.png`, `POST /f/{token}`.
  `FormPageService.url(form)` builds `baseUrl + "/f/" + token`; the form's `action` is the absolute path
  `"/f/" + token`. `ChatbotForm` carries `workspaceId`.
- Pages expire after `omnichannel.pages.ttl-days` (90).
- `CloudMediaController` also uses the base address to build an API media URL; that is not a customer-facing page.
- `SecurityConfig` permits `GET /**` (the console's files and routes), `POST /t/*/e` and `POST /f/*`; `/api/**`
  needs a login. `HttpAddressGuard` (common) refuses private and loopback targets unless configured otherwise.
- A brand is a `Workspace` (`customerId`, `name`). Platform-only authorities (for example `plan:manage`) have no
  default role, so only the super admin holds them.
- The app is not deployed yet; the plan is AWS on minimal spend.

## 4. Approaches

- **A. The customer forwards to us.** Their web server or CDN receives the request and forwards our paths; they hold
  the certificate. Works for a subdomain and for a bare domain; needs someone on their side who can configure it.
- **B. We issue the certificate automatically (chosen for subdomains).** A small web server (Caddy) in front of the
  app terminates HTTPS. The customer adds one DNS record. On the first request for a host name Caddy asks the app
  whether the name is known and, if so, obtains and renews a free certificate itself.
- C. AWS-managed certificates per domain. Rejected: a manual request per domain, limits, and a load balancer's
  monthly cost.

**Chosen (user: "your pick"): the app is built so A and B both work; B is deployed at go-live, A covers bare domains
and customers who prefer their own proxy.** The app side is the same for both.

## 5. Design

Naming: "link address" on screens; `PagesAddress` in code (the existing word for these pages is "pages").

### 5.1 What an address is

A link address is `https://<host>[/<path>]`:
- scheme `https` (also `http` when `omnichannel.pages.allow-insecure` is true: tests and local runs only);
- host: a DNS name, stored lower-case; not an IP address; no port (a port only with `allow-insecure`);
- path: optional, at most 3 segments of `[A-Za-z0-9_-]`, stored without a trailing slash; no query, fragment or
  user info. The path exists for a bare domain: `https://bimalinstitute.com/wa` forwards one folder and cannot clash
  with the customer's own `/t/…` or `/f/…`. **The customer's proxy must remove the path**: we always serve
  `/t/{token}` and `/f/{token}`.

Refused when saved: the platform address's host (pointless), and a host that a brand of **another customer** uses
or used recently (§5.3). Brands of the same customer may share an address.

### 5.2 Data

- A new collection `pagesAddresses`, one document per brand that has or had an address (`Workspace` has no
  `@Version` and is saved whole on rename and disable, so the address does not live on it):
  `workspaceId (unique), customerId, baseUrl, host, status (UNCHECKED | WORKING | FAILING), checkedAt, lastError,
  failures (consecutive), checkNonce, setAt, setBy, formerHosts [{host, until}], @Version`. `baseUrl`, `host` and
  the status fields are null when the brand has no address now (the document stays for its former hosts). The
  daily pass and the check write their fields with guarded `$set` updates that do not touch `version`; the screen's
  saves use `version`.
- `formerHosts`: one entry per host the brand stopped using, with `until = when it stopped + pages TTL`. Entries
  past `until` are dropped whenever the brand's address is written.
- Index on `host` and on `formerHosts.host` (plain, not unique).
- `MessagePage.baseUrl` and `ChatbotForm.baseUrl`: the address the link was built with (null on rows from before:
  the platform address).
- The platform address, when saved on the screen: a singleton document, following the `BillingSettings` pattern
  (`pagesSettings`, one document, `baseUrl, updatedAt, updatedBy`, `@Version`). Until one is saved, the startup
  property is the platform address, as today. The saved one wins over the property; the screen says which is in use.
- `Authority.PAGES_ADDRESS_MANAGE("pages-address:manage", PLATFORM, "Set the link addresses of the platform and of
  brands")`, no default role (super admin only). Authority pins go up by one.

### 5.3 Which address a link uses

`PagesAddresses` (service) is the only place that answers it:
- `platform()`: the saved platform address, else the startup property.
- `forWorkspace(workspaceId)`: the brand's address **when its status is `WORKING`**, else `platform()`.
- `hostKnown(host)`: the platform host, any brand's current host (whatever its status: the certificate is needed
  before the check can pass), or a former host whose `until` is in the future.
- `customerHost(host)`: a brand's current or former host that is not the platform host.

`MessagePageService` and `FormPageService` take the base from `forWorkspace` when they create a page or form, store
it on the row, build the link from the stored value, and render every absolute URL (`og:image`, `og:url`, the
beacon, the form's `action`, media and action links) from it. Previews (`MessagePageService.previewHtml`, the
chatbot editor's preview) have no row and use `forWorkspace` of the brand being edited. `CloudMediaController`
uses `platform()`. Nothing else reads `props.pages().baseUrl()`.

Rows from before this change get the platform address stored once, by a start-up migration with a marker in
`dataMigrations` (as `ChatbotSettingsMigration`): `baseUrl = platform()` on every `MessagePage` and `ChatbotForm`
without one. So a page always renders with the address its link was sent with, and a later change of the platform
address cannot turn an old page's beacon into a cross-origin call. A row that still has none (created between the
migration's read and write) falls back to `platform()`.

So a brand's links move to its domain only once the domain works, fall back to the platform's the moment it is
marked failing, and a page keeps the address it was sent with for its whole life.

### 5.4 The check

`check(workspaceId)`:
1. writes a fresh random `checkNonce` on the address;
2. `GET <baseUrl>/t/-/check/<nonce>` with a 20 s timeout (a new subdomain's first request is also the one that
   makes the edge fetch its certificate, which takes several seconds), no redirects followed, through an
   `HttpAddressGuard` of its own (`allow-insecure` lets it pass loopback, for tests; the existing guard takes its
   switch from the chatbot settings and is not reused);
3. the public endpoint `GET /t/-/check/{nonce}` answers `200` with the body `omnichannel-pages <nonce>` only when
   some workspace's address holds that nonce, else `404` (the token `-` cannot collide: page tokens never are `-`);
4. `WORKING` when the answer is `200` with exactly that body; otherwise the status and `lastError` say why in
   plain words ("The address did not answer", "The address answered, but not from this server", "The certificate is
   not valid", "The name does not exist").

Saving an address sets `UNCHECKED` and runs the check at once; like a manual check, its one result decides
(`WORKING` or `FAILING` with the reason). There is no retry job: the screen says a new subdomain can need a minute
for its certificate and offers Check again. A daily pass (on an existing scheduler, one per
installation) checks every brand address again: a success sets `WORKING` and `failures = 0`; a failure adds one, and
the third in a row sets `FAILING`. A manual check sets the status from its one result.

The platform address has no status: it is the fallback. Saving it runs the same request once and shows the result
as a warning only.

### 5.5 Old links

- The app serves `/t/**` and `/f/**` whatever the `Host`, so a page opens through any address that reaches us.
- Changing or removing a brand's address moves the old host to `formerPagesHosts` with `until`. While `until` is in
  the future the host stays known (§5.6), so its certificate keeps renewing; the customer keeps the DNS record or
  the forwarding for those 90 days (the screen says so when an address is changed).
- Changing the platform address: the old platform host is kept the same way, in the settings document
  (`formerHosts`).

### 5.6 Certificates on demand (approach B)

- `GET /internal/pages/host-allowed?domain=<host>` answers `200` when `hostKnown(host)`, else `404`. It answers
  only a direct caller on the loopback interface: a request that carries `X-Forwarded-For`, `Forwarded` or `Via`
  (anything the edge proxied from outside, which also arrives from loopback) gets `404`, as does any other remote
  address. `omnichannel.pages.ask-from` can name other caller addresses.
- `docs/deploy/Caddyfile` (new) shows the edge: on-demand TLS with `ask` set to that endpoint, and a reverse proxy
  to the app; the platform host is served by the same block. `project-rules.md` §2 and the README gain the steps:
  what the customer adds (a CNAME record to the platform host for a subdomain; forwarding of `<path>/t/` and
  `<path>/f/` to the platform address, path removed, for a bare domain), and that a known host gets its
  certificate on the first request.
- Nothing in the app depends on Caddy: behind any proxy that brings the request to the app, the same design works.

### 5.7 A customer's domain serves pages only

A servlet filter, before security: when the request's host (the `Host` header, or the first `X-Forwarded-Host`
when `omnichannel.pages.trust-forwarded-host` is true) is a `customerHost`, only `GET|POST /t/**` and `/f/**` are
served; anything else is `404` with no body. Requests on the platform host, and on hosts nobody registered
(an IP, `localhost`, a health check), behave as today.

### 5.8 API (all need `pages-address:manage`)

- `GET /api/platform/pages-address` → `{baseUrl, source: "SAVED" | "STARTUP", startupBaseUrl, version}`;
  `PUT` `{baseUrl | null, version}` → the same plus `warning` (the check's failure text, or null). `null` removes
  the saved address, so the startup value (`OMNICHANNEL_PAGES_BASE_URL`, else `appConfig.pagesBaseUrl`) is in use
  again: on 8089 nothing is saved, and a new tunnel is still followed by starting once with the variable.
- `GET /api/customers/{id}/pages-addresses` → one row per brand:
  `{workspaceId, workspaceName, address: {baseUrl, status, checkedAt, lastError, inUse} | null, formerHosts:
  [{host, until}], version}` and, once, `platformBaseUrl` (what the customer points a CNAME at).
- `PUT /api/customers/{id}/workspaces/{workspaceId}/pages-address` `{baseUrl | null, version}` → the row (checked);
  `400` field `baseUrl` for a bad address, `409 PAGES_HOST_TAKEN` for another customer's host, `409` on a stale
  version.
- `POST /api/customers/{id}/workspaces/{workspaceId}/pages-address/check` → the row.

### 5.9 Console

- **Customer page** (`CustomerDetailPage`, shown with `pages-address:manage`): a card "Link addresses", one row per
  brand: the address or "Uses the platform address", a status label (Working / Not checked / Failing, with the
  reason and when it was last checked), and Set…, Check, Remove. The Set dialog takes the address and shows, from
  what is typed, what the customer must do: for a subdomain "Add a CNAME record for user-task.bimalinstitute.com
  pointing to <platform host>"; with a path or a bare domain "Forward https://bimalinstitute.com/wa/t/ and
  …/wa/f/ to <platform address>, removing /wa". Changing or removing says the old address must keep working for
  90 days for links already sent, and lists former hosts with their end date.
- **Platform → Link address** (`/platform/link-address`, same authority): the platform address, whether it comes
  from startup settings or was saved here, Save, "Use the startup setting" (removes the saved one), and the
  warning when the check fails.
- A brand's own users see nothing new.

## 6. Constraints

Java 21, Spring Boot 4.1, Jackson 3, MongoDB standalone (guarded single-document updates, `@Version` on what
screens save), React 18, PatternFly 6.5. Collections camelCase. "Template" means Meta templates only. Work in
`.worktrees/link-addresses` in both repos; test first; Prettier on touched files only; one Maven build at a time;
the full `./mvnw -q verify` once before a merge. No real message without the user naming the recipient. The
existing `appConfig.pagesBaseUrl` and `OMNICHANNEL_PAGES_BASE_URL` keep working as the startup value.

## 7. Left to the builder

Class and package layout (`service.page` is the natural home); how the daily pass is scheduled; the HTTP client for
the check (the JDK client behind `RestClient`, as `ChatbotHttpClient`); index names; the exact error-to-text
mapping beyond the four texts; console component split and copy beyond the strings fixed above; the Caddyfile's
details.

## 8. Verification

Backend, with embedded Mongo and `MutableClock` (`allow-insecure` on, the check pointed at the test server's own
port through a host alias or a stubbed client):
- address validation: each refusal of §5.1; another customer's current and former host → `409`; same customer → ok;
- a brand without an address, with an `UNCHECKED` one and with a `FAILING` one sends links under the platform
  address; with a `WORKING` one under its own, for message pages and form pages; `og:image`, `og:url`, the beacon
  and the form action carry the same base, path prefix included;
- a page created under an old address still renders with that address after the brand's address changes;
- the check: success, wrong body, timeout, redirect not followed, nonce of another brand → not working; the three
  failures rule of the daily pass and recovery;
- former hosts: known until `until`, unknown after, pruned on the next write;
- `host-allowed`: platform host, current host of any status, former host in time → 200; unknown → 404; a
  non-loopback caller → 404;
- the filter: on a customer host `/t/x` and `/f/x` are served and `/`, `/login`, `/api/users/me` are 404; on the
  platform host and on an unregistered host everything is as today;
- platform address: saved value wins over the startup one, is used by new links at once without a restart, old
  platform host kept as former;
- authority pins; `pages-address:manage` required on every new endpoint.

Console (vitest): the card's states and actions; the dialog's instruction text for a subdomain and for a path; the
409 texts; the platform page; hidden without the authority; `tsc`, lint, build.

Manual on 8089 (when the user asks): with `allow-insecure`, a `*.localhost` address for Demo Fashion, check it, send
a message page to a recipient the user names and see the link under the brand's address; a real domain and Caddy
are checked at deployment.

## 9. Risks

- The customer must keep the old DNS record or forwarding for 90 days after a change, or links already sent stop
  opening; we can only say so.
- A brand's domain that breaks is noticed by the daily pass after three days; until then its new links do not open.
  A manual Check marks it at once.
- Let's Encrypt limits certificates per registered domain per week (50); one customer with dozens of brand
  subdomains set up in one week could hit it.
- The check fetches the address from our own server; on a network that cannot reach its own public address
  (some NAT set-ups) a working address would read as failing.
