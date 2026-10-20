# New Chat (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /inbox/phone-numbers/{pn}/chats` starts (or lands in) the chat of a phone number and sends its first message through the existing send path.

**Architecture:** A new `NewChatService` validates the phone, checks the device, resolves or builds the chat and delegates to `InboxSendService.send`. `InboxSendService.record` learns to save a chat that has no id yet, so a chat is only stored once the device accepted the first message.

**Tech Stack:** Spring Boot 4.1, Spring Data Mongo, Lombok, Jackson 3 (`tools.jackson.*`), JUnit 5 + MockMvc / `TestRestTemplate` + `FakeDevice`.

**Spec:** `docs/superpowers/specs/2026-10-21-new-chat-design.md` (reads with `2026-10-21-chat-inbox-design.md` §5, §6, §8).

## Global Constraints

- Lombok accessors; no hand-written getters/setters.
- Error codes are upper snake case: `CHAT_TAKEN` is 409; validation errors use `ValidationException(field, message)` → 400 `VALIDATION` with `fields`.
- Phone validation message is exactly `must be 8 to 15 digits with the country code, e.g. 919812345678` (same as `ContactService.create`).
- `CHAT_TAKEN` message is exactly `This number already has a chat assigned to someone else`.
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Tests: `./mvnw -q test -Dtest=NewChatIT` for the task, `./mvnw -q test` for the suite (flapdoodle embedded Mongo; ~2 min).

## Review Focus

- A phone typed as `+91 98123-45678` must normalise to `919812345678` and work (Task 2 test `formattedPhoneIsNormalised`).
- An agent starting a chat that belongs to another agent must get 409 `CHAT_TAKEN` and the device must receive nothing (Task 2 test `chatAssignedToSomeoneElseIsRefused`).
- A device error on a brand-new number must leave no chat row (Task 2 test `deviceFailureLeavesNoChat`).
- A chat the caller may see but that is assigned to someone else (manager) is posted into without reassignment (Task 2 test `visibleChatOfSomeoneElseIsUsedWithoutReassigning`).
- Starting a chat with a CLOSED existing chat reopens it with a `REOPENED` action before sending (Task 2 test `closedChatIsReopened`).

---

### Task 1: `InboxSendService` stores a chat that has no id yet

**Files:**
- Modify: `src/main/java/me/nageshk/omnichannel/inbox/InboxSendService.java` (`record`, lines ~267-300)
- Modify: `src/main/java/me/nageshk/omnichannel/inbox/InboxDevices.java`
- Test: `src/test/java/me/nageshk/omnichannel/inbox/InboxSendIT.java`

**Interfaces:**
- Produces: `InboxSendService.send(AuthenticatedUser, String workspaceId, WaWebChat chat, SendRequest)` accepts a `WaWebChat` whose `id` is null (customerId, workspaceId, phoneNumberId, remoteId, contactId, status, assignment set); on success the chat is saved and the message's `chatId` is its new id. `InboxDevices.requireReady(String workspaceId, String phoneNumberId)` returning `DeviceSession`, same exceptions as the chat overload (which now delegates to it).

- [ ] **Step 1: Write the failing test** in `InboxSendIT`:

```java
@Test
void unsavedChatIsStoredWithTheFirstMessage() throws Exception {
    Contact contact = contact(brand, "Nia", "919811111111");
    WaWebChat fresh = new WaWebChat();
    fresh.setCustomerId(acme.getId());
    fresh.setWorkspaceId(brand.getId());
    fresh.setPhoneNumberId(number.getId());
    fresh.setRemoteId("919811111111@c.us");
    fresh.setContactId(contact.getId());
    fresh.setStatus(ChatStatus.OPEN);
    fresh.setAssignedUserId(agent.getId());

    try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
        device.send(Envelope.event("ready", ready()));
        Thread.sleep(200);
        CompletableFuture<MessageView> sent = sender.send(access.load(agent), brand.getId(), fresh,
                new SendRequest("hi Nia", null, null));
        Envelope req = device.next();
        device.send(Envelope.response(req.id(), model(fresh, "true_919811111111@c.us_1", "hi Nia", "chat", 1)));
        MessageView view = sent.get(5, TimeUnit.SECONDS);

        WaWebChat stored = chats.findByPhoneNumberIdAndRemoteId(number.getId(), "919811111111@c.us").orElseThrow();
        assertThat(view.chatId()).isEqualTo(stored.getId());
        assertThat(stored.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(stored.getLastMessage().text()).isEqualTo("hi Nia");
        assertThat(messages.findByPhoneNumberIdAndWaMessageId(number.getId(), "true_919811111111@c.us_1")
                .orElseThrow().getChatId()).isEqualTo(stored.getId());
    }
}

@Test
void unsavedChatFallsBackToTheRowAnInboundMessageCreatedMeanwhile() throws Exception {
    // the same remote id gets a chat before the send's record step runs: the existing row wins
    WaWebChat existing = chat("Nia", "919811111111", 1);
    existing.setRemoteId("919811111111@c.us");
    existing = chats.save(existing);
    WaWebChat fresh = new WaWebChat();
    fresh.setCustomerId(acme.getId());
    fresh.setWorkspaceId(brand.getId());
    fresh.setPhoneNumberId(number.getId());
    fresh.setRemoteId("919811111111@c.us");
    fresh.setContactId(existing.getContactId());
    fresh.setStatus(ChatStatus.OPEN);
    fresh.setAssignedUserId(agent.getId());

    try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
        device.send(Envelope.event("ready", ready()));
        Thread.sleep(200);
        CompletableFuture<MessageView> sent = sender.send(access.load(agent), brand.getId(), fresh,
                new SendRequest("hi again", null, null));
        Envelope req = device.next();
        device.send(Envelope.response(req.id(), model(fresh, "true_919811111111@c.us_2", "hi again", "chat", 1)));
        MessageView view = sent.get(5, TimeUnit.SECONDS);

        assertThat(view.chatId()).isEqualTo(existing.getId());
        assertThat(chats.count()).isEqualTo(1);
        assertThat(chats.findById(existing.getId()).orElseThrow().getAssignedUserId()).isNull();
    }
}
```

Add `@Autowired InboxSendService sender;` to the test. `MessageView` is a record: check its accessor name for the chat id (`chatId()`).

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=InboxSendIT`
Expected: FAIL — `message.setChatId(null)` leaves the message without a chat / `chats.findById(null)` throws `IllegalArgumentException`.

- [ ] **Step 3: Implement**

In `InboxSendService.record`, resolve the chat **before** building the message:

```java
WaWebChat chat = sent.getId() == null
        ? chats.findByPhoneNumberIdAndRemoteId(sent.getPhoneNumberId(), sent.getRemoteId()).orElse(sent)
        : chats.findById(sent.getId()).orElse(sent);
if (chat.getId() == null) chat = chats.save(chat);
```

then `message.setChatId(chat.getId())` instead of `sent.getId()`, and at the end `foldOutbound(chat, …)` + `chats.save(chat)` as today. A `DuplicateKeyException` from the save is retried by the existing `InboxIngestService.retrying` wrapper around `record`, and the retry finds the row by remote id. Update the Javadoc of `send` to say the chat may be unsaved.

In `InboxDevices`, add `DeviceSession requireReady(String workspaceId, String phoneNumberId)` with the current body, and make the chat overload call it with `chat.getPhoneNumberId()`.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=InboxSendIT`
Expected: PASS, all existing `InboxSendIT` tests still green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/inbox/InboxSendService.java src/main/java/me/nageshk/omnichannel/inbox/InboxDevices.java src/test/java/me/nageshk/omnichannel/inbox/InboxSendIT.java
git commit -m "feat(inbox): send into a chat that is stored with its first message"
```

---

### Task 2: `NewChatService` and `POST /phone-numbers/{pn}/chats`

**Files:**
- Create: `src/main/java/me/nageshk/omnichannel/inbox/NewChatRequest.java`
- Create: `src/main/java/me/nageshk/omnichannel/inbox/NewChatService.java`
- Create: `src/main/java/me/nageshk/omnichannel/inbox/ChatTakenException.java`
- Modify: `src/main/java/me/nageshk/omnichannel/inbox/InboxController.java` (constructor + one endpoint)
- Modify: `src/main/java/me/nageshk/omnichannel/api/ApiExceptionHandler.java` (one handler next to `chatClosed`)
- Modify: `docs/superpowers/specs/2026-10-21-chat-inbox-design.md` §10 API summary: add the endpoint line.
- Test: `src/test/java/me/nageshk/omnichannel/inbox/NewChatIT.java`

**Interfaces:**
- Consumes: Task 1's unsaved-chat `send` and `InboxDevices.requireReady(workspaceId, phoneNumberId)`; `ContactService.upsertFromChat(workspaceId, customerId, remote, null, null, null)`; `ChatAssignmentService.reopen(me, chat)` / `.assign(me, chat, userId, null)`; `InboxScopeService.scopeOf(me, workspaceId)` + `InboxScope.covers(chat)`; `Phones.normalise/isValid`; `PhoneNumberRepository.findByIdAndWorkspaceId`.
- Produces:
  - `public record NewChatRequest(String phone, String text, String templateId, Map<String, String> values)`.
  - `public class ChatTakenException extends RuntimeException { public static final String CODE = "CHAT_TAKEN"; }` message `This number already has a chat assigned to someone else`.
  - `NewChatService.start(AuthenticatedUser me, String workspaceId, String phoneNumberId, NewChatRequest req): CompletableFuture<MessageView>`.
  - `POST /api/workspaces/{ws}/inbox/phone-numbers/{phoneNumberId}/chats` (`inbox:send`) → `CompletableFuture<MessageView>`.

- [ ] **Step 1: Write the failing tests** `NewChatIT` (copy the `FakeDevice`, `ready()`, `model()` and `viaDevice`-style helpers from `InboxSendIT`, pointed at `inbox() + "/phone-numbers/" + number.getId() + "/chats"`; `post(token, body)` without a chat). Tests:

```java
@Test
void newNumberCreatesContactChatAndMessageAssignedToTheSender() throws Exception {
    Exchange ex = sendOk(agentToken, Map.of("phone", "919811111111", "text", "hello"),
            "919811111111@c.us", "true_919811111111@c.us_1", "hello");

    assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(ex.request().params().get("chatId").asString()).isEqualTo("919811111111@c.us");
    WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(number.getId(), "919811111111@c.us").orElseThrow();
    assertThat(ex.response().getBody().get("chatId").asString()).isEqualTo(chat.getId());
    assertThat(chat.getStatus()).isEqualTo(ChatStatus.OPEN);
    assertThat(chat.getAssignedUserId()).isEqualTo(agent.getId());
    assertThat(chat.getAssignedAt()).isNotNull();
    assertThat(chat.getLastMessage().text()).isEqualTo("hello");
    Contact contact = contacts.findById(chat.getContactId()).orElseThrow();
    assertThat(contact.getPhone()).isEqualTo("919811111111");
    assertThat(contact.getWaId()).isEqualTo("919811111111@c.us");
    assertThat(actions.count()).isZero();
}

@Test
void formattedPhoneIsNormalised() throws Exception {
    Exchange ex = sendOk(agentToken, Map.of("phone", "+91 98111-11111", "text", "hello"),
            "919811111111@c.us", "true_919811111111@c.us_1", "hello");
    assertThat(ex.request().params().get("chatId").asString()).isEqualTo("919811111111@c.us");
}

@Test
void invalidPhoneIs400AndTouchesNothing() throws Exception {
    // device connected so the failure is the phone, not NO_DEVICE
    ... connect FakeDevice + ready ...
    ResponseEntity<JsonNode> res = post(agentToken, Map.of("phone", "12", "text", "hello"));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(res.getBody().get("fields").get("phone").asString())
            .isEqualTo("must be 8 to 15 digits with the country code, e.g. 919812345678");
    assertThat(device.poll(Duration.ofMillis(300))).isEmpty();
    assertThat(chats.count()).isZero();
    assertThat(contacts.count()).isZero();
}

@Test
void noDeviceIs409BeforeAnythingIsWritten() throws Exception {
    ResponseEntity<JsonNode> res = post(agentToken, Map.of("phone", "919811111111", "text", "hello"));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(res.getBody().get("code").asString()).isEqualTo("NO_DEVICE");
    assertThat(chats.count()).isZero();
    assertThat(contacts.count()).isZero();
}

@Test
void deviceFailureLeavesNoChat() throws Exception {
    Exchange ex = viaDevice(agentToken, Map.of("phone", "919811111111", "text", "hello"),
            req -> Envelope.errorResponse(req.id(), "CHAT_NOT_FOUND", "Chat not found"));
    assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(chats.count()).isZero();
    assertThat(messages.count()).isZero();
}

@Test
void chatAssignedToSomeoneElseIsRefused() throws Exception {
    WaWebChat theirs = chat("Nia", "919811111111", 1, agent2, null);
    theirs.setRemoteId("919811111111@c.us");
    chats.save(theirs);
    ... connect FakeDevice + ready ...
    ResponseEntity<JsonNode> res = post(agentToken, Map.of("phone", "919811111111", "text", "hello"));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(res.getBody().get("code").asString()).isEqualTo("CHAT_TAKEN");
    assertThat(res.getBody().get("message").asString())
            .isEqualTo("This number already has a chat assigned to someone else");
    assertThat(device.poll(Duration.ofMillis(300))).isEmpty();
    assertThat(chats.count()).isEqualTo(1);
}

@Test
void visibleChatOfSomeoneElseIsUsedWithoutReassigning() throws Exception {
    WaWebChat theirs = chat("Nia", "919811111111", 1, agent2, null);
    theirs.setRemoteId("919811111111@c.us");
    theirs = chats.save(theirs);
    Exchange ex = sendOk(managerToken, Map.of("phone", "919811111111", "text", "hello"),
            "919811111111@c.us", "true_919811111111@c.us_1", "hello");
    assertThat(ex.response().getBody().get("chatId").asString()).isEqualTo(theirs.getId());
    assertThat(chats.count()).isEqualTo(1);
    assertThat(chats.findById(theirs.getId()).orElseThrow().getAssignedUserId()).isEqualTo(agent2.getId());
    assertThat(actions.count()).isZero();
}

@Test
void unassignedChatIsClaimedByTheCaller() throws Exception {
    WaWebChat queue = chat("Nia", "919811111111", 1, null, support);
    queue.setRemoteId("919811111111@c.us");
    queue = chats.save(queue);
    Exchange ex = sendOk(leadToken, Map.of("phone", "919811111111", "text", "hello"),
            "919811111111@c.us", "true_919811111111@c.us_1", "hello");
    assertThat(ex.response().getBody().get("chatId").asString()).isEqualTo(queue.getId());
    WaWebChat after = chats.findById(queue.getId()).orElseThrow();
    assertThat(after.getAssignedUserId()).isEqualTo(lead.getId());
    assertThat(after.getAssignedTeamId()).isEqualTo(support.getId()); // lead is a member, team kept
    assertThat(actions.findAll()).extracting(WaWebChatAction::getType).containsExactly(ActionType.ASSIGNED);
}

@Test
void closedChatIsReopened() throws Exception {
    WaWebChat closed = chat("Nia", "919811111111", 1, agent, null);
    closed.setRemoteId("919811111111@c.us");
    closed.setStatus(ChatStatus.CLOSED);
    closed = chats.save(closed);
    Exchange ex = sendOk(agentToken, Map.of("phone", "919811111111", "text", "hello"),
            "919811111111@c.us", "true_919811111111@c.us_1", "hello");
    assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(chats.findById(closed.getId()).orElseThrow().getStatus()).isEqualTo(ChatStatus.OPEN);
    assertThat(actions.findAll()).extracting(WaWebChatAction::getType).containsExactly(ActionType.REOPENED);
}

@Test
void templateIsRenderedForTheNewContact() throws Exception {
    // a TEXT template "Hi {{contact.name}}" with the name placeholder defaulting to "there" (see InboxSendIT.nameSetting)
    String templateId = textTemplate("Welcome", "Hi {{contact.name}}", nameSetting());
    Exchange ex = sendOk(agentToken, Map.of("phone", "919811111111", "templateId", templateId, "values", Map.of()),
            "919811111111@c.us", "true_919811111111@c.us_1", "Hi there");
    assertThat(ex.request().params().get("content").asString()).isEqualTo("Hi there");
    assertThat(ex.response().getBody().get("templateId").asString()).isEqualTo(templateId);
}

@Test
void requiresInboxSend() throws Exception {
    // a user with inbox:view only: create a role without inbox:send the way InboxApiIT does for forbidden cases,
    // or use the `other` workspace's token pattern; expect 403.
}

@Test
void numberOfAnotherBrandIs404() throws Exception {
    ResponseEntity<JsonNode> res = post(agentToken, Map.of("phone", "919811111111", "text", "hello"),
            inbox() + "/phone-numbers/" + otherNumber.getId() + "/chats");
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
}
```

Look at `InboxApiIT` for how a forbidden (403) case is built before writing `requiresInboxSend`; if no cheap pattern exists, drop that test and say so in the report.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=NewChatIT`
Expected: FAIL to compile (`NewChatRequest`, `ChatTakenException` missing) — then 404s once stubs exist.

- [ ] **Step 3: Implement**

`NewChatService` (`@Service`, constructor-injected: `PhoneNumberRepository numbers`, `InboxDevices devices`, `InboxScopeService scopes`, `WaWebChatRepository chats`, `ContactService contacts`, `ChatAssignmentService assignment`, `InboxSendService sender`, `Clock clock`):

```java
public CompletableFuture<MessageView> start(AuthenticatedUser me, String workspaceId, String phoneNumberId,
        NewChatRequest req) {
    String phone = Phones.normalise(req == null ? null : req.phone());
    if (!Phones.isValid(phone)) throw new ValidationException("phone", "must be 8 to 15 digits with the country code, e.g. 919812345678");
    PhoneNumber number = numbers.findByIdAndWorkspaceId(phoneNumberId, workspaceId).orElseThrow(() -> new NotFoundException("phone number not found"));
    devices.requireReady(workspaceId, phoneNumberId);
    String remoteId = phone + "@c.us";
    SendRequest send = new SendRequest(req.text(), req.templateId(), req.values());

    WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(phoneNumberId, remoteId).orElse(null);
    if (chat != null) {
        if (chat.getAssignedUserId() != null && !scopes.scopeOf(me, workspaceId).covers(chat)) throw new ChatTakenException();
        if (chat.getStatus() == ChatStatus.CLOSED) chat = assignment.reopen(me, chat);
        if (chat.getAssignedUserId() == null) chat = assignment.assign(me, chat, me.userId(), null);
        return sender.send(me, workspaceId, chat, send);
    }
    Contact contact = contacts.upsertFromChat(workspaceId, number.getCustomerId(), remoteId, null, null, null);
    // build the unsaved chat: customerId, workspaceId, phoneNumberId, remoteId, contactId, OPEN,
    // assignedUserId = me.userId(), assignedAt = clock.instant()
    return sender.send(me, workspaceId, fresh, send);
}
```

Check `PhoneNumber`'s customer accessor name and whether `InboxScope.covers` / `scopeOf` are the real names (`InboxScopeService`, `InboxScope`) before using them. Validation of text/template stays in `InboxSendService`, but note the order: an invalid phone is reported before the device check, and the text/template check happens inside `send` (after assignment side effects on an existing chat; acceptable, the spec puts the device check first and says nothing about the body check order — do not reorder `send`).

Controller:

```java
/** Starts (or lands in) the chat of a phone number and sends its first message; see {@link NewChatService}. */
@PostMapping("/phone-numbers/{phoneNumberId}/chats")
@PreAuthorize("@access.has(#workspaceId, 'inbox:send')")
public CompletableFuture<MessageView> startChat(@AuthenticationPrincipal AuthenticatedUser me,
        @PathVariable String workspaceId, @PathVariable String phoneNumberId,
        @RequestBody(required = false) NewChatRequest req) {
    return newChats.start(me, workspaceId, phoneNumberId, req);
}
```

`ApiExceptionHandler`: `@ExceptionHandler(ChatTakenException.class)` → `body(HttpStatus.CONFLICT, ChatTakenException.CODE, e.getMessage())`.

Spec §10 of the inbox design: add `POST /phone-numbers/{pn}/chats {phone, text | templateId+values}` → MessageView (inbox:send).

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=NewChatIT`
Expected: PASS.

- [ ] **Step 5: Full suite**

Run: `./mvnw -q test 2>&1 | tail -30`
Expected: `Tests run: …, Failures: 0, Errors: 0` (no output at all from `-q` on success is also a pass; check the exit code).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/nageshk/omnichannel/inbox/NewChatRequest.java src/main/java/me/nageshk/omnichannel/inbox/NewChatService.java src/main/java/me/nageshk/omnichannel/inbox/ChatTakenException.java src/main/java/me/nageshk/omnichannel/inbox/InboxController.java src/main/java/me/nageshk/omnichannel/api/ApiExceptionHandler.java src/test/java/me/nageshk/omnichannel/inbox/NewChatIT.java docs/superpowers/specs/2026-10-21-chat-inbox-design.md
git commit -m "feat(inbox): start a chat with a phone number from the console"
```
