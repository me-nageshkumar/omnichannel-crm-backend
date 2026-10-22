# Number Lookup (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A cached "is this number on WhatsApp" lookup, exposed as a GET and enforced by `NewChatService` before a first message to an unknown number.

**Architecture:** `NumberLookupService` reads a `waNumberLookups` cache, otherwise asks the device's `getNumberId` RPC and stores the answer. `NewChatService.start` looks a number up only when no chat is known for it, refuses unregistered numbers, and creates the chat under the id the lookup reported. `InboxSendService.send` learns the typed phone so the contact of a lid chat gets it.

**Tech Stack:** Spring Boot 4.1, Spring Data Mongo, Lombok, Jackson 3 (`tools.jackson.*`), JUnit 5 + `TestRestTemplate` + `FakeDevice` (RANDOM_PORT).

**Spec:** `docs/superpowers/specs/2026-10-21-number-lookup-design.md` §3 (reads with `2026-10-21-new-chat-design.md` §2).

## Global Constraints

- Lombok accessors; no hand-written getters/setters.
- Codes and messages, verbatim: `NOT_ON_WHATSAPP` (409) `This number is not on WhatsApp`; `LOOKUP_FAILED` (502) `Could not check this number on WhatsApp, try again`; phone validation `ValidationException("phone", "must be 8 to 15 digits with the country code, e.g. 919812345678")` (400 `VALIDATION`).
- Cache freshness: 30 days for a registered answer, 7 days for an unregistered one, measured from `checkedAt` against the injected `Clock`.
- RPC method `getNumberId`, params `{"phone": "<digits>"}`, result `{"phone": "<digits>@c.us", "lid": "<id>@lid" | null}` or JSON `null`.
- `NumberLookup` JSON: `{phone, registered, remoteId, checkedAt, cached}`; `remoteId` is the lid when known, else the `@c.us` id, null when not registered.
- Tests: `./mvnw -q test -Dtest=NumberLookupIT` / `-Dtest=NewChatIT` for a task, `./mvnw -q test` for the suite (~2 min, flapdoodle embedded Mongo).
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

- A cached "not registered" must never refuse a chat that already exists for the number (Task 2 test `cachedNotRegisteredDoesNotBlockAnExistingChat`).
- A lookup that fails on the device must send nothing and store no chat, contact or cache row (Task 2 test `lookupFailureIs502AndSendsNothing`, Task 1 test `deviceErrorIs502AndNothingIsCached`).
- A lid-only chat (hidden contact without a phone) assigned to someone else must still be `CHAT_TAKEN` once the lookup reveals the lid, with no send (Task 2 test `lidOnlyChatOfSomeoneElseIsRefusedAfterLookup`).
- A registered number whose contact already exists by phone must end with one contact carrying both phone and lid, both when the device's `message_create` event beats the reply and when it does not (Task 2 test `lookupLidOfAKnownContactIsLinked[WhenTheEventComesFirst]`).
- The request thread's wait is bounded: a device that never answers the lookup must produce `LOOKUP_FAILED` after `omnichannel.rpc.timeout`, not hang (Task 1 test `silentDeviceIs502AfterTheTimeout`).

---

### Task 1: `NumberLookupService`, cache and `GET /phone-numbers/{pn}/number-lookup`

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/inbox/WaNumberLookup.java`
- Create: `src/main/java/me/nageshk/omnichannel/inbox/WaNumberLookupRepository.java`
- Create: `src/main/java/me/nageshk/omnichannel/inbox/NumberLookup.java`
- Create: `src/main/java/me/nageshk/omnichannel/inbox/LookupFailedException.java`
- Create: `src/main/java/me/nageshk/omnichannel/inbox/NumberLookupService.java`
- Modify: `src/main/java/me/nageshk/omnichannel/inbox/InboxController.java` (new GET next to `startChat`)
- Modify: `src/main/java/me/nageshk/omnichannel/api/ApiExceptionHandler.java` (`LookupFailedException` → 502, also in `wrapped`)
- Modify: `src/test/java/me/nageshk/omnichannel/inbox/InboxIndexesIT.java`
- Test: `src/test/java/me/nageshk/omnichannel/inbox/NumberLookupIT.java`

**Interfaces:**
- Produces:
  - `@Document("waNumberLookups") @CompoundIndex(name = "ws_phone", def = "{'workspaceId': 1, 'phone': 1}", unique = true) class WaNumberLookup { @Id String id; String workspaceId; String phoneNumberId; String phone; boolean registered; String waPhone; String lid; Instant checkedAt; }` (Lombok `@Getter @Setter`).
  - `interface WaNumberLookupRepository extends MongoRepository<WaNumberLookup, String> { Optional<WaNumberLookup> findByWorkspaceIdAndPhone(String workspaceId, String phone); }`
  - `public record NumberLookup(String phone, boolean registered, String remoteId, Instant checkedAt, boolean cached)`.
  - `public class LookupFailedException extends RuntimeException { public static final String CODE = "LOOKUP_FAILED"; }` message as in Global Constraints.
  - `@Service public class NumberLookupService { public CompletableFuture<NumberLookup> lookup(String workspaceId, String phoneNumberId, String phone, boolean force); }` — `phone` is normalised by the service (`Phones.normalise`) and a bad one throws `ValidationException("phone", …)` synchronously; an unknown number throws `NotFoundException("phone number not found")` synchronously (before any cache read, so the GET is 404 even on a cache hit); device problems throw `NoDeviceException` synchronously (via `InboxDevices.requireReady(workspaceId, phoneNumberId)`), only when the cache cannot answer. The future fails with `LookupFailedException` for any `RpcException` or `TimeoutException` from the device.
  - `GET /api/workspaces/{ws}/inbox/phone-numbers/{pn}/number-lookup?phone=…&force=false` → `CompletableFuture<NumberLookup>`, `@PreAuthorize("@access.has(#workspaceId, 'inbox:send')")`.

- [ ] **Step 1: Write the failing tests** `NumberLookupIT` (RANDOM_PORT, `properties = "omnichannel.rpc.timeout=2s"`, extends `InboxApiTestBase`; copy `ready()` and the `FakeDevice` pattern from `NewChatIT`; `@Autowired WaNumberLookupRepository lookups; @Autowired NumberLookupService service;`; `get(token, phone, force)` does `rest.exchange(url + "?phone=" + phone + (force ? "&force=true" : ""), GET, bearer, JsonNode.class)` with `url = "http://localhost:" + port + inbox() + "/phone-numbers/" + number.getId() + "/number-lookup"`). `@BeforeEach` clears `lookups` (the base class cleans the inbox collections; add `lookups.deleteAll()`).

Helper: `viaDevice(token, phone, force, Function<Envelope, Envelope> answer)` like `NewChatIT.viaDevice` but asserting `req.method()` is `getNumberId`, and `lookupAnswer(String phone, String lid)` building `mapper.createObjectNode().put("phone", phone).put("lid", lid)` (`putNull("lid")` when null).

```java
@Test
void registeredNumberWithLidIsCached() throws Exception {
    Exchange ex = viaDevice(agentToken, "919811111111", false,
            req -> Envelope.response(req.id(), lookupAnswer("919811111111@c.us", "103238296682612@lid")));
    assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(ex.request().params().get("phone").asString()).isEqualTo("919811111111");
    JsonNode body = ex.response().getBody();
    assertThat(body.get("registered").asBoolean()).isTrue();
    assertThat(body.get("remoteId").asString()).isEqualTo("103238296682612@lid");
    assertThat(body.get("cached").asBoolean()).isFalse();
    WaNumberLookup row = lookups.findByWorkspaceIdAndPhone(brand.getId(), "919811111111").orElseThrow();
    assertThat(row.getLid()).isEqualTo("103238296682612@lid");
    assertThat(row.getWaPhone()).isEqualTo("919811111111@c.us");
    assertThat(row.getPhoneNumberId()).isEqualTo(number.getId());
}

@Test
void registeredWithoutLidReportsTheCusId() throws Exception {
    Exchange ex = viaDevice(agentToken, "919811111111", false,
            req -> Envelope.response(req.id(), lookupAnswer("919811111111@c.us", null)));
    assertThat(ex.response().getBody().get("remoteId").asString()).isEqualTo("919811111111@c.us");
}

@Test
void unregisteredNumberIsCached() throws Exception {
    Exchange ex = viaDevice(agentToken, "919811111111", false,
            req -> Envelope.response(req.id(), mapper.nullNode()));
    JsonNode body = ex.response().getBody();
    assertThat(body.get("registered").asBoolean()).isFalse();
    assertThat(body.get("remoteId").isNull()).isTrue();
    assertThat(lookups.findByWorkspaceIdAndPhone(brand.getId(), "919811111111").orElseThrow().isRegistered()).isFalse();
}

@Test
void freshCacheHitSkipsTheDevice() throws Exception {
    seed("919811111111", true, "103238296682612@lid", Duration.ofDays(29));
    ResponseEntity<JsonNode> res = getUntouched(agentToken, "919811111111", false);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody().get("cached").asBoolean()).isTrue();
    assertThat(res.getBody().get("remoteId").asString()).isEqualTo("103238296682612@lid");
}

@Test
void cacheHitNeedsNoDevice() throws Exception {
    seed("919811111111", true, "103238296682612@lid", Duration.ofDays(1));
    ResponseEntity<JsonNode> res = get(agentToken, "919811111111", false); // no device connected at all
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
}

@Test
void staleRegisteredAnswerIsReQueried() throws Exception {
    seed("919811111111", true, "old@lid", Duration.ofDays(31));
    Exchange ex = viaDevice(agentToken, "919811111111", false,
            req -> Envelope.response(req.id(), lookupAnswer("919811111111@c.us", "new@lid")));
    assertThat(ex.response().getBody().get("remoteId").asString()).isEqualTo("new@lid");
    assertThat(lookups.count()).isEqualTo(1);
}

@Test
void staleUnregisteredAnswerIsReQueriedAfterAWeek() throws Exception {
    seed("919811111111", false, null, Duration.ofDays(8));
    Exchange ex = viaDevice(agentToken, "919811111111", false,
            req -> Envelope.response(req.id(), lookupAnswer("919811111111@c.us", null)));
    assertThat(ex.response().getBody().get("registered").asBoolean()).isTrue();
}

@Test
void forceReQueriesAFreshAnswer() throws Exception {
    seed("919811111111", false, null, Duration.ofHours(1));
    Exchange ex = viaDevice(agentToken, "919811111111", true,
            req -> Envelope.response(req.id(), lookupAnswer("919811111111@c.us", null)));
    assertThat(ex.response().getBody().get("registered").asBoolean()).isTrue();
    assertThat(ex.response().getBody().get("cached").asBoolean()).isFalse();
}

@Test
void deviceErrorIs502AndNothingIsCached() throws Exception {
    Exchange ex = viaDevice(agentToken, "919811111111", false,
            req -> Envelope.errorResponse(req.id(), "PAGE_ERROR", "boom"));
    assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(ex.response().getBody().get("code").asString()).isEqualTo("LOOKUP_FAILED");
    assertThat(ex.response().getBody().get("message").asString())
            .isEqualTo("Could not check this number on WhatsApp, try again");
    assertThat(lookups.count()).isZero();
}

@Test
void silentDeviceIs502AfterTheTimeout() throws Exception {
    // device receives the request and never answers; omnichannel.rpc.timeout=2s
    try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
        device.send(Envelope.event("ready", ready()));
        Thread.sleep(200);
        CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(() -> get(agentToken, "919811111111", false));
        assertThat(device.next().method()).isEqualTo("getNumberId");
        ResponseEntity<JsonNode> res = http.get(10, TimeUnit.SECONDS);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(res.getBody().get("code").asString()).isEqualTo("LOOKUP_FAILED");
    }
}

@Test
void noDeviceIs409() throws Exception {
    ResponseEntity<JsonNode> res = get(agentToken, "919811111111", false);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(res.getBody().get("code").asString()).isEqualTo("NO_DEVICE");
}

@Test
void badPhoneIs400() throws Exception {
    ResponseEntity<JsonNode> res = get(agentToken, "12", false);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(res.getBody().get("fields").get("phone").asString())
            .isEqualTo("must be 8 to 15 digits with the country code, e.g. 919812345678");
}

@Test
void formattedPhoneIsNormalisedAsTheCacheKey() throws Exception {
    seed("919811111111", true, "x@lid", Duration.ofDays(1));
    ResponseEntity<JsonNode> res = get(agentToken, "+91 98111-11111", false);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody().get("phone").asString()).isEqualTo("919811111111");
}

@Test
void numberOfAnotherBrandIs404EvenWhenCached() throws Exception {
    seed("919811111111", true, "x@lid", Duration.ofDays(1));
    ResponseEntity<JsonNode> res = getAt(agentToken, "http://localhost:" + port + inbox() + "/phone-numbers/"
            + otherNumber.getId() + "/number-lookup?phone=919811111111");   // otherNumber: base-class fixture of another brand
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
}

@Test
void requiresInboxSend() throws Exception {
    Role viewOnly = new Role();
    viewOnly.setName("INBOX_VIEWER");
    viewOnly.setLevel(RoleLevel.BRAND);
    viewOnly.setAuthorities(Set.of("inbox:view"));
    viewOnly = roles.save(viewOnly);
    User viewer = testUsers.createInCustomer(acme.getId(), "viewer", null,
            List.of(new WorkspaceRole(brand.getId(), viewOnly.getId())));
    ResponseEntity<JsonNode> res = get(testUsers.accessToken(viewer), "919811111111", false);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
}
```

`seed(phone, registered, lid, age)` saves a `WaNumberLookup` for `brand` with `checkedAt = Instant.now().minus(age)`, `waPhone = phone + "@c.us"` when registered. `getUntouched` connects a ready `FakeDevice`, performs the GET and asserts `device.poll(Duration.ofMillis(300))` is empty. For `+` in a query string, URL-encode the phone (`UriUtils.encodeQueryParam` or `%2B`).

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=NumberLookupIT`
Expected: compilation failure (classes missing).

- [ ] **Step 3: Implement**

`NumberLookupService` (`@Service`, `@RequiredArgsConstructor`): `WaNumberLookupRepository lookups; PhoneNumberRepository numbers; InboxDevices devices; RpcDispatcher dispatcher; ObjectMapper mapper; Clock clock`. Constants `REGISTERED_FRESH = Duration.ofDays(30)`, `UNREGISTERED_FRESH = Duration.ofDays(7)`, `PHONE_MESSAGE` as in `NewChatService` (move it to `Phones.MESSAGE` and use it from both, or duplicate; prefer a `public static final String MESSAGE` on `Phones`).

```java
public CompletableFuture<NumberLookup> lookup(String workspaceId, String phoneNumberId, String rawPhone, boolean force) {
    String phone = Phones.normalise(rawPhone);
    if (!Phones.isValid(phone)) throw new ValidationException("phone", Phones.MESSAGE);
    numbers.findByIdAndWorkspaceId(phoneNumberId, workspaceId).orElseThrow(() -> new NotFoundException("phone number not found"));
    Instant now = clock.instant();
    if (!force) {
        Optional<WaNumberLookup> hit = lookups.findByWorkspaceIdAndPhone(workspaceId, phone).filter(l -> fresh(l, now));
        if (hit.isPresent()) return CompletableFuture.completedFuture(view(hit.get(), true));
    }
    DeviceSession device = devices.requireReady(workspaceId, phoneNumberId);
    ObjectNode params = mapper.createObjectNode().put("phone", phone);
    return dispatcher.call(device, "getNumberId", params)
            .handle((result, error) -> {
                if (error != null) throw new LookupFailedException(); // RpcException / TimeoutException, possibly wrapped in CompletionException
                return view(InboxIngestService.retrying(() -> store(workspaceId, phoneNumberId, phone, result, clock.instant())), false);
            });
}
```

`store` finds-or-creates the row by `(workspaceId, phone)`, sets `registered = result != null && result.isObject()`, `waPhone = registered ? result.path("phone").asString() : null`, `lid = registered && result.hasNonNull("lid") ? result.get("lid").asString() : null`, `phoneNumberId`, `checkedAt`, saves. `fresh(l, now)` = `l.getCheckedAt() != null && now.isBefore(l.getCheckedAt().plus(l.isRegistered() ? REGISTERED_FRESH : UNREGISTERED_FRESH))`. `view(l, cached)` = `new NumberLookup(l.getPhone(), l.isRegistered(), l.isRegistered() ? (l.getLid() != null ? l.getLid() : l.getWaPhone()) : null, l.getCheckedAt(), cached)`.

Controller: `@GetMapping("/phone-numbers/{phoneNumberId}/number-lookup")` with `@RequestParam String phone, @RequestParam(defaultValue = "false") boolean force` → `lookups.lookup(workspaceId, phoneNumberId, phone, force)`.

`ApiExceptionHandler`: `@ExceptionHandler(LookupFailedException.class)` → `body(HttpStatus.BAD_GATEWAY, LookupFailedException.CODE, e.getMessage())`; in `wrapped`, `if (cause instanceof LookupFailedException l) return lookupFailed(l);`.

`InboxIndexesIT`: add `WaNumberLookup.class` to `clean()` and a test `numberLookupIsUniquePerWorkspaceAndPhone` saving two rows with the same `(workspaceId, phone)` → `DuplicateKeyException`, plus an index-name assertion (`ws_phone`, unique) in the style of the file's existing index tests.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=NumberLookupIT,InboxIndexesIT`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/inbox/WaNumberLookup.java src/main/java/me/nageshk/omnichannel/inbox/WaNumberLookupRepository.java src/main/java/me/nageshk/omnichannel/inbox/NumberLookup.java src/main/java/me/nageshk/omnichannel/inbox/LookupFailedException.java src/main/java/me/nageshk/omnichannel/inbox/NumberLookupService.java src/main/java/me/nageshk/omnichannel/inbox/InboxController.java src/main/java/me/nageshk/omnichannel/api/ApiExceptionHandler.java src/main/java/me/nageshk/omnichannel/contact/Phones.java src/test/java/me/nageshk/omnichannel/inbox/NumberLookupIT.java src/test/java/me/nageshk/omnichannel/inbox/InboxIndexesIT.java
git commit -m "feat(inbox): cached WhatsApp number lookup"
```

---

### Task 2: `NewChatService` looks unknown numbers up; the typed phone travels with the send

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/inbox/NotOnWhatsAppException.java`
- Modify: `src/main/java/me/nageshk/omnichannel/inbox/NewChatService.java`
- Modify: `src/main/java/me/nageshk/omnichannel/inbox/InboxSendService.java` (`send` overload, `afterSend`, `record`)
- Modify: `src/main/java/me/nageshk/omnichannel/api/ApiExceptionHandler.java` (`NotOnWhatsAppException` → 409, also in `wrapped`)
- Modify: `src/main/java/me/nageshk/omnichannel/inbox/NewChatRequest.java` only if needed (it is not)
- Test: `src/test/java/me/nageshk/omnichannel/inbox/NewChatIT.java`

**Interfaces:**
- Consumes: Task 1's `NumberLookupService.lookup` and `NumberLookup`.
- Produces: `InboxSendService.send(AuthenticatedUser me, String workspaceId, WaWebChat chat, SendRequest req, String typedPhone)`; the four-argument `send` delegates with `null`. `NotOnWhatsAppException` (`CODE = "NOT_ON_WHATSAPP"`).

Behaviour of `start` after this task (spec §3.4):
1. Validate phone, number, device as now. `existingChat(ws, pn, phone, phone + "@c.us")` as now; found → `sendToExisting(me, ws, chat, message)` (today's `CHAT_TAKEN` / reopen / claim / `sender.send` block, extracted) with no lookup.
2. Else `NumberLookup lookup = await(lookups.lookup(ws, pn, phone, false))` where `await` is `try { return f.join(); } catch (CompletionException e) { if (e.getCause() instanceof RuntimeException r) throw r; throw e; }`.
3. `!lookup.registered()` → `throw new NotOnWhatsAppException()`.
4. `remoteId = lookup.remoteId()`; `chats.findByPhoneNumberIdAndRemoteId(pn, remoteId)` present → `sendToExisting`.
5. Else build the fresh chat as now but with `remoteId` from the lookup, and call `sender.send(me, ws, fresh, message, phone)`.

`InboxSendService.afterSend` gets `typedPhone` (threaded from `send`): `String lidPhone = typedPhone;` then the existing adoption block only fills `lidPhone` from `phoneOf(sent.getRemoteId())` when it is still null. `record` keeps its signature; its contact branch becomes `lidPhone != null && chat.getRemoteId().endsWith("@lid") ? contacts.linkLidToPhone(…) : contacts.upsertFromChat(…)` (an `@c.us` remote id derives the phone from itself, spec §3.4 precedence).

- [ ] **Step 1: Update the `NewChatIT` helpers, then write the failing tests.**

`viaDevice(token, body, answer)` gains the lookup: before expecting `sendMessage`, it takes `Envelope req = device.next(); if ("getNumberId".equals(req.method())) { device.send(Envelope.response(req.id(), lookupAnswer.apply(req))); req = device.next(); }`. Add an overload `viaDevice(token, body, Function<Envelope, JsonNode> lookupAnswer, Function<Envelope, Envelope> answer)`; the existing three-argument one passes a default lookup answer of `{"phone": "<typed digits>@c.us", "lid": null}` (registered, no lid), which keeps every existing test's `chatId` of `919811111111@c.us` and keeps the lid-adoption tests meaningful (the device still reports the lid on the send). `@Autowired WaNumberLookupRepository lookupRows;` cleaned in a `@BeforeEach`. `postUntouched` is unchanged (the existing tests that use it must still see no device request, which proves the "existing chat needs no lookup" rule for `lidChatOfSomeoneElseIsRefused` and `chatAssignedToSomeoneElseIsRefused`).

New tests:

```java
private static final String LID = "103238296682612@lid";

private ObjectNode lookupAnswer(String phone, String lid) { … }  // as in NumberLookupIT

/** Posts with a device that answers the lookup as given and must then receive nothing. */
private ResponseEntity<JsonNode> postAfterLookup(String token, Object body, JsonNode lookup) throws Exception {
    try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
        device.send(Envelope.event("ready", ready()));
        Thread.sleep(200);
        CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(() -> { try { return post(token, body); } catch (Exception e) { throw new IllegalStateException(e); } });
        Envelope req = device.next();
        assertThat(req.method()).isEqualTo("getNumberId");
        device.send(lookup == null ? Envelope.errorResponse(req.id(), "PAGE_ERROR", "boom") : Envelope.response(req.id(), lookup));
        ResponseEntity<JsonNode> res = http.get(5, TimeUnit.SECONDS);
        assertThat(device.poll(Duration.ofMillis(300))).isEmpty();
        return res;
    }
}

@Test
void numberNotOnWhatsAppIs409AndTouchesNothing() throws Exception {
    ResponseEntity<JsonNode> res = postAfterLookup(agentToken, Map.of("phone", "919811111111", "text", "hello"), mapper.nullNode());
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(res.getBody().get("code").asString()).isEqualTo("NOT_ON_WHATSAPP");
    assertThat(res.getBody().get("message").asString()).isEqualTo("This number is not on WhatsApp");
    assertThat(chats.count()).isZero();
    assertThat(contacts.count()).isZero();
    assertThat(messages.count()).isZero();
    assertThat(lookupRows.findByWorkspaceIdAndPhone(brand.getId(), "919811111111")).isPresent();
}

@Test
void lookupFailureIs502AndSendsNothing() throws Exception {
    ResponseEntity<JsonNode> res = postAfterLookup(agentToken, Map.of("phone", "919811111111", "text", "hello"), null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(res.getBody().get("code").asString()).isEqualTo("LOOKUP_FAILED");
    assertThat(chats.count()).isZero();
    assertThat(contacts.count()).isZero();
    assertThat(lookupRows.count()).isZero();
}

private void lookupLidPutsTheChatUnderIt(boolean eventFirst, Contact known) throws Exception {
    String serialized = "true_" + LID + "_9";
    Exchange ex = viaDevice(agentToken, Map.of("phone", "919811111111", "text", "hello"),
            req -> lookupAnswer("919811111111@c.us", LID),
            req -> {
                if (eventFirst) publisher.publishEvent(new WhatsAppEvent(this, number.getId(), brand.getId(), acme.getId(),
                        "message_create", model(LID, serialized, "hello"), null, System.currentTimeMillis()));
                return Envelope.response(req.id(), model(LID, serialized, "hello"));
            });
    assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(ex.request().params().get("chatId").asString()).isEqualTo(LID);   // addressed to the lid, not @c.us
    assertThat(chats.count()).isEqualTo(1);
    WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(number.getId(), LID).orElseThrow();
    assertThat(chat.getAssignedUserId()).isEqualTo(agent.getId());
    assertThat(contacts.count()).isEqualTo(1);
    Contact contact = contacts.findById(chat.getContactId()).orElseThrow();
    assertThat(contact.getPhone()).isEqualTo("919811111111");
    assertThat(contact.getWaId()).isEqualTo(LID);
    if (known != null) { assertThat(contact.getId()).isEqualTo(known.getId()); assertThat(contact.getName()).isEqualTo(known.getName()); }
}

@Test void lookupLidPutsTheChatUnderTheLid() throws Exception { lookupLidPutsTheChatUnderIt(false, null); }
@Test void lookupLidPutsTheChatUnderTheLidWhenTheEventComesFirst() throws Exception { lookupLidPutsTheChatUnderIt(true, null); }
@Test void lookupLidOfAKnownContactIsLinked() throws Exception { lookupLidPutsTheChatUnderIt(false, knownContact()); }
@Test void lookupLidOfAKnownContactIsLinkedWhenTheEventComesFirst() throws Exception { lookupLidPutsTheChatUnderIt(true, knownContact()); }

@Test
void cachedNotRegisteredDoesNotBlockAnExistingChat() throws Exception {
    seedLookup("919811111111", false, null);                       // stale-proof: checkedAt = now
    WaWebChat existing = chatOf("919811111111@c.us", "Nia", agent, null);
    Exchange ex = sendOk(agentToken, Map.of("phone", "919811111111", "text", "hello"),
            "919811111111@c.us", "true_919811111111@c.us_1", "hello");   // first device request must be sendMessage
    assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(ex.response().getBody().get("chatId").asString()).isEqualTo(existing.getId());
}

@Test
void cachedLookupSkipsTheDevice() throws Exception {
    seedLookup("919811111111", true, LID);
    Exchange ex = sendOk(agentToken, Map.of("phone", "919811111111", "text", "hello"), LID, "true_" + LID + "_1", "hello");
    assertThat(ex.request().params().get("chatId").asString()).isEqualTo(LID);   // and no getNumberId frame was needed
}

/** A chat ingest created under a lid whose contact never got a phone. */
private WaWebChat hiddenLidChat(User assignedTo) {
    Contact c = new Contact(); c.setWorkspaceId(brand.getId()); c.setCustomerId(acme.getId()); c.setWaId(LID);
    c.setSource(me.nageshk.omnichannel.contact.ContactSource.CHAT);
    Contact hidden = contacts.save(c);
    WaWebChat chat = chat(number, brand, hidden, 1);
    chat.setRemoteId(LID);
    chat.setAssignedUserId(assignedTo == null ? null : assignedTo.getId());
    return chats.save(chat);
}

@Test
void lidOnlyChatIsFoundThroughTheLookup() throws Exception {
    WaWebChat lid = hiddenLidChat(agent);
    Exchange ex = viaDevice(agentToken, Map.of("phone", "919811111111", "text", "hello"),
            req -> lookupAnswer("919811111111@c.us", LID),
            req -> Envelope.response(req.id(), model(LID, "true_" + LID + "_1", "hello")));
    assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(ex.response().getBody().get("chatId").asString()).isEqualTo(lid.getId());
    assertThat(chats.count()).isEqualTo(1);
    assertThat(contacts.count()).isEqualTo(1);
}

@Test
void lidOnlyChatOfSomeoneElseIsRefusedAfterLookup() throws Exception {
    hiddenLidChat(agent2);
    ResponseEntity<JsonNode> res = postAfterLookup(agentToken, Map.of("phone", "919811111111", "text", "hello"),
            lookupAnswer("919811111111@c.us", LID));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(res.getBody().get("code").asString()).isEqualTo("CHAT_TAKEN");
    assertThat(chats.count()).isEqualTo(1);
}
```

`seedLookup(phone, registered, lid)` saves a `WaNumberLookup` for `brand` with `checkedAt = Instant.now()`, `waPhone = phone + "@c.us"` when registered. Note on `lidOnlyChatIsFoundThroughTheLookup`: the existing hidden contact has no phone; after the send the contact count stays 1 because the chat is saved (has an id) and `record` only touches contacts for unsaved chats. Document that in the test's comment.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=NewChatIT`
Expected: compilation failure (`NotOnWhatsAppException`, `send` overload missing); after stubbing, the new tests fail and the existing ones fail because no `getNumberId` is asked.

- [ ] **Step 3: Implement** as described under Interfaces. `NotOnWhatsAppException` mirrors `ChatTakenException`. Handler: `@ExceptionHandler(NotOnWhatsAppException.class)` → 409, and a branch in `wrapped`.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=NewChatIT,InboxSendIT,NumberLookupIT`
Expected: PASS, including every pre-existing `NewChatIT` test.

- [ ] **Step 5: Full suite**

Run: `./mvnw -q test`
Expected: exit 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/inbox/NotOnWhatsAppException.java src/main/java/me/nageshk/omnichannel/inbox/NewChatService.java src/main/java/me/nageshk/omnichannel/inbox/InboxSendService.java src/main/java/me/nageshk/omnichannel/api/ApiExceptionHandler.java src/test/java/me/nageshk/omnichannel/inbox/NewChatIT.java
git commit -m "feat(inbox): new chat refuses numbers that are not on WhatsApp and starts under the lid"
```
