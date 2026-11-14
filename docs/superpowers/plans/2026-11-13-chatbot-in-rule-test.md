# Chatbot in the rule test: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A rule test's Start chatbot step starts a test run (drafts allowed), records the run, and the console takes
the tester to the chatbot's live view with a way back.

**Architecture:** `ActionContext` learns the tester's user id; `ActionResult`/`RunStep`/`StepView` carry
`chatbotId` + `chatbotRunId`; `StartChatbotHandler` branches on `ctx.test()`. Console: the test page navigates after
Run, the editor reads `back`, run logs link the run.

**Tech Stack:** Java 21 / Spring Boot 4.1; React 18 / PatternFly 6.5 / vitest.

**Spec:** `docs/superpowers/specs/2026-11-13-chatbot-in-rule-test-design.md`

## Global Constraints
- Project rules §1 (worktree, test first, Prettier on touched files, trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`).
- Query parameters: `run`, `back`. Link texts: "Back to rule test", "Open run".

## Review Focus
1. A rule test whose Start chatbot action names a chatbot of another brand: the step must fail, never start it.
2. Replace in a test against a live customer run: the live run is stopped (as spec §4.1 says); the reason names it.
3. Old stored steps without `chatbotRunId` (null) render without a link and without errors.
4. The `back` parameter must be a same-app path (`/automations/...`); anything else is ignored.
5. The run started from a test counts as Test on the Runs page and never dedupes with a live (event, rule) run.

---

### Task 1: Start chatbot in a rule test (backend)
**Files:** modify `service/automation/{ActionContext, ActionResult, StartChatbotHandler, RuleRunner, AutomationTestService, AutomationQueryService, StepView}.java`, `db/automation/RunStep.java`; test `app/chatbot/StartChatbotActionIT.java` (+ `AutomationTestsApiIT` untouched).
**Interfaces:** `ActionContext(..., boolean test, String userId)`; `ActionResult(outcome, reason, String chatbotId, String chatbotRunId)` with `done(reason)` etc. keeping nulls and `started(reason, chatbotId, runId)`; `RunStep(index, type, summary, outcome, reason, at, chatbotId, chatbotRunId)`; `StepView(index, type, summary, outcome, reason, at, chatbotId, chatbotRunId)`.
- [ ] Tests in `StartChatbotActionIT`: `ruleTestStartsADraftAsATestRun` (API: draft rule with Start chatbot → draft bot; `POST /rules/{id}/tests {contactId}`; incoming "Hi"; `worker.tick()`; `POST /tests/{t}/actions/0/run` → step `outcome DONE`, `reason "Started “Lead qualifier”"`, `chatbotId`, `chatbotRunId`; stored run `test == true`, `startedBy.userId == manager`, `startedBy.eventId == null`, `event.message.text == "Hi"`); `ruleTestRespectsIfRunningSkip` (another chatbot's run active → step SKIPPED, no run); `liveStepCarriesTheRunId` (extend `publishedRuleStartsChatbotOnMessage`: step `chatbotRunId == run.id`); `otherBrandChatbotFailsInTest` (chatbot of `other` → FAILED "no longer exists", no run).
- [ ] Run `-Dtest=StartChatbotActionIT` → FAIL (compile/404). Implement. Run `-Dtest=StartChatbotActionIT,AutomationTestsApiIT,AutomationHistoryApiIT,ChatbotTestRunIT` → PASS. Commit `feat(automations): a rule test starts a chatbot as a test run and records it`.

### Task 2: Console: go to the run, come back, link from logs
**Files:** `src/app/api/automations.ts` (StepView `chatbotId`, `chatbotRunId`), `Automations/{AutomationTestPage.tsx, useAutomationTest.ts (run resolves the fresh test), RunSteps.tsx}`, `Chatbots/ChatbotEditorPage.tsx` (`back` → `RunOverlayPanel backHref`), `Chatbots/editor/RunOverlayPanel.tsx`; tests `automationTest.test.tsx`, `automationRuns.test.tsx`, `chatbotRuns.test.tsx` or `chatbotEditor*.test.tsx`.
- [ ] Tests: `runningAStartChatbotStepOpensTheChatbot` (afterRun step has `chatbotId: 'c1', chatbotRunId: 'r1'` → location `/chatbots/c1?run=r1&back=%2Fautomations%2Fr1%2Ftest`); `runLogLinksTheChatbotRun` (RunSteps shows "Open run" → `/chatbots/c1?run=r1`; a step without id has no link); `backLinkToTheRuleTest` (editor at `/chatbots/c1?run=r1&back=/automations/r1/test` shows "Back to rule test" → that path; `back=https://evil` ignored).
- [ ] Run RED → implement → `npx vitest run src/app/Automations src/app/Chatbots --maxWorkers=4`, `npx tsc --noEmit && npm run lint` → PASS. Commit `feat(automations): Run on a Start chatbot step opens the chatbot's live view`.

### Task 3: Rules and docs
- [ ] `project-rules.md` §1 sending-hours line (spec `2026-11-13-chatbot-test-on-message-design.md` §5 wording, now in this spec's intent); README (step fields, draft chatbots in rule tests); known-limits (the rule test is the event path; the test idles out after 30 min while you are on the canvas); main spec §7 cross-reference. Commit `docs(automations): chatbot in the rule test, sending-hours rule`.

### Task 4: Deploy the branch on 8089 and check the example
- [ ] Build console + backend worktrees, restart 8089 from the worktree jar. The browser walkthrough needs Claude in Chrome.
