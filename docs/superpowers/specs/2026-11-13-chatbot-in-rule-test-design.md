# Chatbot in the rule test

An addendum to `2026-11-13-advanced-chatbot-design.md` §3.1 and §7, agreed with the user on 2026-11-13 after a
discussion (not "skip the questions"): calls the user made are marked **(the user's)**, mine **(my call)**.

## 1. Intent

The user's way of testing a chatbot: decide the event, configure the chatbot as the rule's Start chatbot action, open
the rule's test page, trigger the event from the test phone, watch the test page show the captured event, each
condition's result and the action ready to run, click Run, and be taken to the chatbot's execution view where the run
is drawn live. Most chatbots start on an incoming message, so this is the first-class test; the editor's own Test
dialog stays for bots that open the conversation.

## 2. Goals, non-goals, anti-goals

- Goals: a draft chatbot runs from a rule test as a test run; the Run click lands on the chatbot editor's live view
  with a way back to the rule test; the step records which run it started (also in the live run log).
- Non-goals: a draft chatbot on a live rule (still refused, spec §3.3); pausing published rules for the test contact.
- Anti-goal: a second way to do the same thing. The "wait for the contact's message" test mode built earlier on
  2026-11-13 is **removed** **(the user's)**: branch `chatbot-test-on-message` was never pushed and is dropped; only
  its `project-rules.md` sending-hours change is carried over.

## 3. Approaches

- A. Keep the editor's "wait for message" mode and also link the rule test to the chatbot: two ways to test the same
  event path. Rejected **(the user's)**.
- **B. The rule test is the event path; the editor's Test dialog starts the bot at once.** Picked.
- C. A new combined "test chatbot with event" page: more UI for what the rule test page already shows. Rejected.

## 4. Behaviour

### 4.1 The Start chatbot step in a rule test (`ActionContext.test() == true`)
- Starts a **test run** (`ChatbotRun.test = true`), `startedBy = (ruleId, ruleName, eventId = null, userId = the
  tester)`. A draft chatbot is accepted; a live rule keeps failing "is not published".
- "If running" applies as it does live (Skip → the step is skipped "Chatbot X is running"; Replace → the running run is
  stopped). Do-not-contact is respected as live.
- The step is written **as soon as the run exists** (no 60 s wait for the first wait) **(my call)**: the person watches
  the run on the canvas. Reason text: `Started “Lead qualifier”`.
- The step carries `chatbotId` and `chatbotRunId` (`RunStep`, `StepView`), for test and live runs alike; the live path
  keeps its 60 s wait and wording.

### 4.2 Console
- Rule test page: when a Run click's response shows the step just run has a `chatbotRunId`, the page navigates to
  `/chatbots/{chatbotId}?run={runId}&back={/automations/{ruleId}/test}` **(the user's: taken there immediately)**.
- Chatbot editor: with `back` in the query, the run panel shows a **Back to rule test** link to it **(my call: in the
  run panel)**. The rule test stays alive on the server (its 30-minute idle rule; the test page polls again on return).
- Run logs (rule runs, event history): a Start chatbot step with a run id shows an **Open run** link to the same view
  (without `back`).

### 4.3 Example
Brand Demo Fashion already holds the draft rule "Hi starts Lead qualifier" (Message received, `message.text is Hi`,
Start chatbot → Lead qualifier, Skip). Test: open the rule → Test → pick Veenu → send "Hi" from 919799750743 → Run.

## 5. Constraints

Project rules §1: worktree `.worktrees/chatbot-in-rule-test`, test first, fresh reviewer, one fix pass, full
`./mvnw -q verify` before a merge; merge, push and deploy only when asked.

## 6. Left to the builder

Exact record and view field names, query-parameter names, link wording and placement.
