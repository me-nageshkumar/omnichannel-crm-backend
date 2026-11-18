# Knowledge bases, moving a number, and the Bimal Institute demo

Design, 2026-11-18.

## 1. Intent

The user is pitching the product to its first customer, **Bimal Institute For Market Research** (Indore; stock
market, crypto and forex trading courses; https://bimalinstitute.com). The pitch shows two things: a WhatsApp
course-enquiry chatbot on the **Main line** that answers like Bimal's own counsellors, and the console behind it. The
user's words: create "a very professional chatbot for their course enquiry using AI node", build "a feature to create
vector store from our application and use it in chatbot", and "design this to put first impression in front of the
customer".

Three parts, built in this order:

1. **Knowledge bases** (a product feature): a brand creates a knowledge base in the console, adds files, web pages
   and typed notes, and an AI profile uses it. Today a profile can only pick a vector store that already exists in the
   OpenAI account.
2. **Move a number** (a small super-admin feature): the Main line belongs to brand Demo Fashion, whose inbox holds real
   people's chats. The prospect must see a clean console, so the Main line moves to a new brand "Bimal Institute" for
   the pitch and back afterwards (the user's choice of three options, 2026-11-18).
3. **The Bimal demo** (data built through the product's own screens): brand, AI connection, knowledge base, AI
   profile, chatbot, rule, team.

## 2. What the user decided

| # | Decision |
|---|---|
| U1 | The console is shown in the pitch, not only the phone. |
| U2 | A new brand "Bimal Institute"; the Main line moves into it for the pitch and back afterwards; old chats stay in Demo Fashion. |
| U3 | A good enquiry ends with the bot knowing name, city, trading experience, course, online or offline, and then one of: **register now** (send the register link), **talk to a counsellor** (hand the chat to Bimal's team with a summary), **visit the lounge** (address and map pin). |
| U4 | Knowledge bases are OpenAI vector stores managed from our app (approach 1 of 3). |
| U5 | The full move with an archive of the old chats, after hearing it rewrites stored chat records ("Switch main line to Bimal Institute"). |

Everything else is marked **(my call)**.

## 3. Approaches

Knowledge bases (the user picked 1):
1. **Our app manages OpenAI vector stores** (chosen): we create the store, upload files and fetched page text, keep a
   record and our own copy; OpenAI searches. Fits the AI node as it is; least work and risk before a pitch; an
   optional SPI ability, so other providers can add it later.
2. Our own embeddings and vector search in MongoDB as an app-side tool: provider-neutral, but much more work, and the
   vector search needed is an Atlas feature, not in our local MongoDB.
3. Facts in the profile instructions, no store: cheap and reliable for a few pages, but not the feature asked for and
   does not grow.

The demo brand (the user picked 1 of three, then confirmed after hearing the real size):
1. **Move the Main line to a new brand, old chats kept in an archive** (chosen).
2. Rename Demo Fashion for the pitch: no build, but real people's chats on screen.
3. A new brand with a spare number: no code, but not the Main line.

## 4. Goals, non-goals, anti-goals

Goals: knowledge bases with files, web pages and notes, their state visible per source, used by AI profiles by name;
moving a number between brands of one customer; a demo that looks made for Bimal.

Non-goals: our own embeddings or vector search; knowledge bases for providers other than OpenAI (the SPI gets the
hooks, no second adapter); crawling a whole site (one address = one page); scheduled re-fetching of web pages
(a **Fetch again** button instead); moving a number to another customer; moving chats with the number.

Anti-goals: the prospect seeing anyone else's messages; the bot giving a stock tip, a buy/sell call or a promise of
profit; the bot inventing a fee, date or offer that is not in the knowledge base; a knowledge base whose console view
says Ready while OpenAI has not finished; an OpenAI key in any response or log (existing rule); a web-page fetch that
reaches our own network (SSRF).

## 5. Facts the design rests on

- AI module (`omnichannel-ai`): `AiProvider` SPI with `vectorStores(c)` listing a key's stores; `AiCapabilities.vectorStores`;
  `AiProfile.vectorStoreIds` (max 50) sent as one `file_search` tool; the console profile form lists the key's stores
  as a checklist under **Knowledge**. Connections are per brand with a sealed key.
- OpenAI: `POST /v1/files` (multipart, `purpose=assistants`, up to 512 MB), `POST /v1/vector_stores {name}`,
  `POST /v1/vector_stores/{vs}/files {file_id}`, `GET /v1/vector_stores/{vs}/files/{file}` with `status`
  `in_progress | completed | failed | cancelled` and `last_error`, `DELETE /v1/vector_stores/{vs}/files/{file}`,
  `DELETE /v1/files/{file}`, `DELETE /v1/vector_stores/{vs}`. Storage is billed per GB per day after the first GB
  free; this demo is a few MB.
- Uploads are capped at 16 MB by `spring.servlet.multipart.max-file-size`; GridFS buckets exist for chat media,
  notes, templates and more. `HttpAddressGuard` (common) is the SSRF guard. pdfbox is on the classpath; jsoup is not.
- Chatbot nodes: send message (text, media, location), ask, form, condition, update contact, do not contact,
  assign/hand over, add to CRM, HTTP, AI (SINGLE / CONVERSATION with outcomes and collect fields), wait, jump.
- Bimal facts (from the website and Nikky's three Main-line messages of 2026-10-22; Nikky is on Bimal's team and
  sent the pitch their counsellors send to a lead):
  - Crypto & Forex Trading Program (CFTP), 45 days, online ₹19,999 / offline ₹24,999 GST included on the website;
    Nikky's pitch says ₹20,000 / ₹25,000. Mentorship Program ₹5,999 (Nikky: 5,900), 30 days handholding, mentors
    Prateek & Koushal; Membership Program ₹9,999 (Nikky: 9,900), 30 days lounge access, for those who finished the
    course.
  - Founded 2016 by Manish Bimal; Vijay Nagar, Indore (Plot 17, near Hotel Silvotel, map 22.746700, 75.897102);
    phone 8889422237; Mon–Sat 9 am–9 pm; register at https://bimalinstitute.com/register-now; Telegram
    https://t.me/manishbimalxswing; YouTube free-course playlist.
  - Refunds: non-refundable except listed cases, within 7 days. No SEBI registration stated; FAQ says "No guaranteed
    profits". Age 16+.
  - Nikky's pitch also sends *CFTP Digital Brochure.pdf* (14.7 MB). We stored only its name; the file must come from
    the user (or a re-download from WhatsApp, if it still serves it).
  - Pitch talking points (not built): the website's WhatsApp button links to the placeholder `wa.me/911234567890`; the
    disclaimer page is a 404.

- Moving a number (survey of the code, 2026-11-18): chats are found by `(phoneNumberId, remoteId)` only, with a
  unique index on that pair (`WaWebChat` `phone_remote_unique`); messages by `(phoneNumberId, waMessageId)`, unique.
  Contacts are per brand (`ws_phone`, `ws_waIds`). The inbox lists a brand's chats by workspaceId and the brand's
  current numbers. The device session keeps a `DevicePrincipal` with the workspaceId read at connect time
  (`DeviceRegistry`); `DeviceAccessChanged` closes it, and a reconnect re-reads the number. History imports carry
  their own workspaceId. A number's team must be a team of its brand. `CampaignSender` is unique per number and
  stores a workspaceId. `PhoneNumber.number` is globally unique. `InboxCleanup.deleteForNumber` lists what is keyed by
  a number (chats, messages, chat actions, history imports and their chats, webhook events, media). The Main line
  holds 75 chats, 567 messages and 15 chatbot runs today. Only the WhatsApp Web path exists (no Cloud webhook).

## 6. Part 1: knowledge bases

### 6.1 What a brand user sees (Brand setup → AI → **Knowledge** tab, third tab after Connections and Profiles)

```
Knowledge                                                     [ New knowledge base ]
┌──────────────────────────────────────────────────────────────────────────────────┐
│ Bimal courses            OpenAI · Bimal key     12 sources · 11 ready · 1 failed  │
│ Used by: Bimal counsellor                                         Updated 18 Nov  │
└──────────────────────────────────────────────────────────────────────────────────┘

Bimal courses                                  [ Add file ] [ Add web page ] [ Add note ]
Sources                                     Kind      Size     State
CFTP Digital Brochure.pdf                   File      14.7 MB  ● Ready
bimalinstitute.com/crypto-forex-trading     Web page  18 KB    ● Ready     Fetch again
Counsellor pitch (Nikky)                    Note      1 KB     ● Ready     Edit
bimalinstitute.com/blog                     Web page  –        ▲ Failed: the page answered 403   Retry
                                                      (rows: ⋮ View text · Remove)
```

- A knowledge base: name (unique in the brand), optional description, connection (one of the brand's connections
  whose provider has `knowledgeBases`), created once; the connection cannot change later (the store lives in that
  account).
- Sources, three kinds:
  - **File**: PDF, DOCX, PPTX, TXT, MD, HTML, up to 16 MB.
  - **Web page**: an https address; we fetch it (through `HttpAddressGuard`, 10 s, 2 MB, no more than 3 redirects,
    each hop re-checked), keep the readable text (title, headings, paragraphs, lists and tables; scripts, styles,
    nav and footer dropped) as Markdown with the address and fetch time at the top, and upload that. **Fetch again**
    replaces it.
  - **Note**: a title and typed text (up to 50,000 characters), uploaded as Markdown. **Edit** replaces it.
- State per source: **Adding** (queued, uploading, or OpenAI indexing) → **Ready** | **Failed: reason** (with
  **Retry**). The list refreshes itself while any source is Adding (every 3 s, as long as the tab is open)
  **(my call)**. Ready is set only after OpenAI reports `completed`.
- **View text**: shows the stored text of a web page or note (files: download our copy).
- Remove a source: confirm; removed from OpenAI and from our store. Delete a knowledge base: refused (409) while a
  profile uses it, naming the profiles; else confirm, then the store and its files go from OpenAI and our copies go.
- The AI profile form's **Knowledge** checklist shows the brand's knowledge bases first, by name with their state,
  (only those on the profile's own connection), then "Other stores in this OpenAI account" (today's list, minus ours) for stores made elsewhere **(my call:
  keeps profiles that use outside stores working)**.
- Authorities: `ai:view` to see, `ai:manage` to change (existing). No new authority.

### 6.2 Backend

- **SPI** (`ai.provider`): `AiCapabilities.knowledgeBases` (OpenAI true). An optional interface
  `KnowledgeStoreProvider` implemented by `OpenAiProvider`: `createStore(c, name) -> storeId`,
  `deleteStore(c, storeId)`, `uploadFile(c, filename, contentType, bytes) -> fileId`, `attach(c, storeId, fileId)`,
  `fileState(c, storeId, fileId) -> {state READY|INDEXING|FAILED, error}`, `detach(c, storeId, fileId)`,
  `deleteFile(c, fileId)`. Missing store/file on delete counts as done.
- **Data** (camelCase, workspace-scoped, kept):
  - `aiKnowledgeBases`: workspaceId, customerId, name, description, connectionId, provider, providerStoreId,
    status (CREATING | READY | FAILED), error, createdAt, createdBy, updatedAt, @Version. Unique {workspaceId, name}.
  - `aiKnowledgeSources`: workspaceId, knowledgeBaseId, kind (FILE | WEB_PAGE | NOTE), title, url, fileName,
    contentType, size, gridFsId (our copy; web pages and notes store their Markdown), providerFileId, state
    (QUEUED | UPLOADING | INDEXING | READY | FAILED | REMOVING), error, attempts, leaseUntil, nextCheckAt, addedAt,
    addedBy, fetchedAt, @Version. Index {state, leaseUntil}, {knowledgeBaseId}.
  - GridFS bucket `aiKnowledgeFiles`.
- **Work on a MongoDB queue with leases** (standing rule; same pattern as `aiCalls`): the source row is the queue item.
  A worker (own small pool, woken on change and by a 5 s tick) claims a row in QUEUED/UPLOADING (upload + attach →
  INDEXING), INDEXING with `nextCheckAt` due (poll → READY, FAILED or check again with backoff 2 s → 30 s, failing
  after 30 min), REMOVING (detach, delete file, delete our copy, delete the row). Transient provider errors retry up to
  3 attempts; AUTH marks the connection INVALID (existing behaviour). The store is created synchronously when the
  knowledge base is created (one quick call; a failure refuses the create with the provider's message)
  **(my call)**. Web pages are fetched by the worker, not in the request.
- **API** (`/api/workspaces/{ws}/ai/knowledge-bases`): `GET` list (with counts per state and `usedBy` profile names),
  `POST` {name, description, connectionId}, `GET/PUT/DELETE {id}` (PUT: name, description), `GET {id}/sources`,
  `POST {id}/sources/file` (multipart), `POST {id}/sources/web-page` {url}, `POST {id}/sources/note` {title, text},
  `PUT {id}/sources/{sid}` (note: title, text; web page: fetch again; failed: retry), `DELETE {id}/sources/{sid}`,
  `GET {id}/sources/{sid}/content` (download our copy). Max 200 sources per knowledge base, 20 knowledge bases per
  brand **(my call)**.
- **Profiles**: profiles keep `vectorStoreIds`; the profile view gains nothing; the console maps ids to knowledge
  bases. Deleting a knowledge base checks profiles whose `vectorStoreIds` contain its store id. Deleting a connection
  that holds a knowledge base → 409 naming it.
- **Replies**: file_search answers may carry citation markers; the WhatsApp text strips OpenAI file-citation markers
  (`【…†…】` and similar) before sending **(my call)**.
- jsoup is added to `omnichannel-ai` for HTML to text.

## 7. Part 2: moving a number

The user chose the full move with an archive (2026-11-18, "Switch main line to Bimal Institute"), after being told
it rewrites stored chat records and that a spare number would need no code.

### 7.1 What the super admin sees

Platform → Phone numbers, row action **Move to brand…** (`phone-number:manage-all`). The dialog:

```
Move Main line (919928088743) from Demo Fashion
To brand   [ Bimal Institute ▾ ]          (brands of the same customer only)

• Its 75 chats stay in Demo Fashion as "Main line (until 18 Nov)", read only.
• New chats start in Bimal Institute. The phone stays linked and reconnects in a few seconds.
• 2 unfinished chatbot runs on this number end. Its team (Sales) is cleared; set one in the new brand.
                                                            [ Cancel ]  [ Move ]
```

Moving back to a brand that holds an archive of this number says instead: "The 3 chats in Bimal Institute stay there
as "Main line (until 25 Nov)". The 75 archived chats in Demo Fashion return to Main line."

The brand's own Phone numbers page and the inbox show an archive as **Archived**, with its chats readable and the
composer replaced by "This number moved to another brand on 18 Nov." No connect code, no team, no enable.

### 7.2 The move, step by step (`PhoneNumberMoveService`, one move at a time per number)

A `phoneNumberMoves` record (kept) holds from, to, archive ids, counts, the step reached, startedAt, finishedAt,
by, error. Every step is safe to run again, so a move that stops half way is finished by **Retry** on the same row
(the console shows "Move did not finish: Retry").

1. Check: same customer, a different brand, the number not already moving. Take the number offline: mark it
   `moving`, which device authentication refuses, and close the device session (`DeviceAccessChanged`, new reason
   MOVED; the key stays valid).
2. End the number's RUNNING/WAITING chatbot runs (ended, reason "number moved") and clear `chat.chatbot`; a chat in
   BOT status becomes open and unassigned **(my call)**; cancel its running history imports.
3. Archive what is there: create an archive `PhoneNumber` in the source brand (label "<label> (until <d MMM>)",
   `archiveOf = <real id>`, `number` absent, disabled, no device fields, **the number's team kept** so the same
   agents still see its chats under their inbox scope **(my call)**), then re-point every record keyed by
   the real id to the archive id: chats, messages, chat actions, history imports and their chats, webhook events,
   media metadata, chatbot runs, message pages, campaign senders, CRM contact channels, activities, notes and tasks.
   A guard test fails if any `@Document` with a `phoneNumberId` field is in neither the re-pointed list nor an
   explicit "left as is" list with a reason.
4. Return what was archived in the target brand: if the target holds an archive of this number, re-point its records
   to the real id and delete that archive record. (A brand holds at most one archive of a number, since moving in
   merges it.)
5. Move the number: `workspaceId = target`, `teamId = null`, `movedInAt = now`, clear `moving`. The phone reconnects
   and its principal is read again.

After a move, a history import on the number takes only messages from `movedInAt` on, and skips chats with none, so
the phone's older conversations never reach the new brand **(my call; anti-goal: nobody else's messages on
screen)**. Archives do not count toward the customer's WhatsApp-number limit **(my call)**. Acks, reactions and votes
for messages that now sit under the archive id are looked up by `waMessageId` across the real id and its archives
**(my call)**.

Index change: `PhoneNumber.number` becomes a unique index on documents that have it (partial), so archives carry no
number; the startup index step replaces the old index.

Messages that arrive in the seconds the phone is offline are what WhatsApp Web delivers on reconnect; the move does
not try to recover more **(my call)**. Contacts are not moved: the new brand makes its own (contacts are per brand).
The old brand's rules that name this number simply stop matching; the dialog lists them by name as a warning.

Endpoints: `POST /api/phone-numbers/{id}/move {workspaceId}` → the move record; `GET /api/phone-numbers/{id}/move`
→ the last move; `POST /api/phone-numbers/{id}/move/retry`; `GET /api/phone-numbers/{id}/move/preview?workspaceId=`
→ the counts and names the dialog shows.

## 8. Part 3: the Bimal demo

Built through the console's own screens (Chrome, the user signs in; the user types the OpenAI key) once parts 1 and 2
are deployed on 8089, so the demo also walks the new features.

- **Brand** "Bimal Institute" under customer Demo Retail **(my call: prospects never see the customer name; moving
  within one customer is simpler)**, team **Counsellors** with the user's agent login in it (who is in the team is
  asked at build time).
- **AI connection** "Bimal OpenAI" (the user's key, typed by the user).
- **Knowledge base** "Bimal courses":
  - web pages: home, crypto-forex-trading, mentorship-program, membership-program, about-us, contact-us,
    register-now, fees-policy, refund-and-cancellation-policy;
  - note "Counsellor pitch" with Nikky's message text as sent;
  - note "Answers the team confirms": where the website and Nikky differ, the website's GST-inclusive fees are the
    ones to quote **(my call; the user can change one note before the pitch)**; batch timings and language are not
    published, so the bot offers a counsellor for them;
  - file *CFTP Digital Brochure.pdf* if the user provides it.
- **AI profile** "Bimal counsellor": gpt-5-mini (as the working test profile), reasoning low, the knowledge base
  checked, instructions in §8.1.
- **Chatbot** "Course enquiry":

```
Start → AI conversation "Counsellor" (outcomes register · counsellor · visit · not_interested;
          collect name, city, experience, course, mode)
   register       → Update contact (name, city) → Add to CRM (pipeline "Admissions", stage "Interested")
                    → Send: register link + fees for the chosen course and mode → Hand over (Counsellors)
   counsellor     → Update contact → Add to CRM → Send: "Our counsellor will message you shortly" → Hand over
   visit          → Update contact → Add to CRM → Send location (Bimal Institute, Vijay Nagar) + hours → Hand over
   not_interested → Send: thanks + YouTube free course + Telegram
   maxTurns / failure → Hand over (Counsellors)
```

  The collect fields are name, city, experience, course, mode and **summary** (two lines for the counsellor). Each
  Update contact step sets the contact's name (if blank) and the attributes City, Experience, Course, Mode and
  Enquiry summary, which the inbox shows beside the chat, so the counsellor who takes the hand-over reads them
  there; the CRM deal title is "{{name}} – {{course}} ({{mode}})" **(my call: data only, no new code)**. The fees in
  the register step come from the AI's finish reply, written from the knowledge base.
  The brochure is offered as a Send document step after the AI's first reply only if the file is provided
  **(my call)**. A CRM pipeline "Admissions" (Interested → Counselled → Registered → Joined) is created for the brand.
- **Rule** "Main line: course enquiry" starts the chatbot for a new conversation on the Main line (chatbot-first
  routing). While the Main line sits in Bimal Institute, real people (Nikky among them) may write to it, so the rule
  carries a condition "contact phone is 919799750743" (the user's test phone) until the user says to open it, for
  instance for the prospect's own phone at the pitch **(my call, from the rule that only a recipient the user names
  gets a real message)**. Others who write meanwhile land in the Bimal inbox unanswered by the bot.
- **Phone check**: the user's phone (919799750743) messages the Main line; real replies go only to that phone.

### 8.1 Profile instructions (the bot's character)

- You are the admissions counsellor of Bimal Institute For Market Research, Indore, on WhatsApp.
- Answer only from the knowledge base. If it does not say, say you will check with the team and offer a counsellor.
  Never invent a fee, a batch date, a discount or an offer.
- Education only: never recommend a stock, coin or currency pair, never give a buy/sell call or a target, never promise
  profit or income; if asked, say trading carries risk and the course teaches risk management.
- Short WhatsApp messages (2–5 lines), one question at a time, warm and confident, no long lists unless asked; mirror
  the customer's language (English, Hindi or Hinglish).
- Learn naturally, not as a form: name, city, trading experience (none / some / active trader), course, online or
  offline. Recommend: beginners → CFTP; finished the course → Membership; wants guided live practice → Mentorship.
- When they want to register, a counsellor, or a visit, confirm once and call finish with that outcome and what you
  learned; if they are not interested, thank them and finish.

## 9. Constraints

Java 21, Spring Boot 4.1, Jackson 3, MongoDB with GridFS (never S3), collections camelCase; React 18 + PatternFly 6.5.
Work in `.worktrees/bimal-pitch` (branch `bimal-pitch`) in both repos, test first,
Prettier on touched files, full `./mvnw -q verify` before each merge. Real sends only to the recipient the user names.
mongodump before deploying. The OpenAI key is typed by the user.

## 10. Left to the builder

The HTML-to-Markdown rules beyond §6.1; worker pool size and tick; the exact backoff; component split; how the
console shows a big list of sources; the demo's exact message texts (within §8).

## 11. Verification

- Backend: OpenAI knowledge calls against the local stub (create store, upload multipart, attach, poll states,
  delete with 404 as done); worker claims, lease re-claim after a crash, backoff, 30-min give-up; web fetch refused for
  private addresses and redirects into them; HTML to Markdown on a saved Bimal page; 409s (profile uses KB, connection
  holds KB); limits; keys absent from responses and logs. Move-number ITs: a move then a message from a known contact opens a new chat in the target brand while the old one stays readable in the archive; acks for old messages still update them; moving back merges the archive and leaves the target's archive; a move stopped after each step finishes on Retry; refused across customers; device refused while moving; the guard test over `phoneNumberId` documents.
- Console: vitest for the Knowledge tab (states, auto refresh stops when none Adding, add/remove/fetch again), the
  profile checklist grouping, the move dialog; tsc, lint, build.
- Live on 8089 with the user's key: the Bimal knowledge base reaches Ready; the bot answers fees, duration, refund,
  location from it; refuses a stock tip; each of the three ways ends in the right steps; the CRM deal appears.
