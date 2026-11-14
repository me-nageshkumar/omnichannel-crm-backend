# Rule summary on hover: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hovering or focusing a rule's name in the automations list shows the rule in plain words.

**Architecture:** The list response carries each rule's conditions and actions; a pure describer in the console turns
them into lines with the wording the editor already uses; a PatternFly hover popover on the name shows them.

**Tech Stack:** Java 21 / Spring Boot 4.1; React 18 / PatternFly 6.5 / vitest.

**Spec:** `docs/superpowers/specs/2026-11-14-rule-hover-summary-design.md`

## Global Constraints
- No new dependency. Wording comes from `conditionText`, `actionSummary`, `eventLabel` (`automationModel.ts`,
  `automationDisplay.ts`); the empty state follows `ConditionTreeEditor`'s "No conditions: every <event phrase> runs this rule."
- Project rules §1: worktree `.worktrees/rule-hover-summary`, test first, Prettier on touched files, trailer
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus
1. A condition whose choice value no longer exists (deleted number, template, chatbot) shows the raw id, never a crash.
2. Options still loading or failed: the popover still opens with raw values.
3. A deeply nested group (group in group in group) stays readable (nested bullets, no lost conditions).
4. A rule with no actions shows "Then: nothing yet" rather than an empty heading.
5. Keyboard: focusing the name link opens the popover; Escape closes it.

---

### Task 1: The list carries conditions and actions (backend)
**Files:** `omnichannel-service/.../automation/RuleSummaryView.java`, `AutomationViews.java:80`; test
`omnichannel-app/.../automation/AutomationRulesApiIT.java` (`listInRunOrderWithStats`).
**Produces:** `RuleSummaryView(..., Instant creationTime, ConditionNode conditions, List<AutomationAction> actions)`.
- [ ] Test: in `listInRunOrderWithStats` give rule B `all(leaf("message.text","is","Hi"))` and one SEND_TEMPLATE
  action; assert `row.get("conditions").get("combinator") == "and"`, `rules[0].field == "message.text"`,
  `row.get("actions").get(0).get("type") == "SEND_TEMPLATE"`; rule A (no conditions) has `conditions` null.
- [ ] Run `./mvnw -q -pl omnichannel-app -am test -Dtest=AutomationRulesApiIT -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.
- [ ] Add the two fields, pass `r.getConditions()`, `r.getActions()`. Run → PASS. Commit
  `feat(automations): the rules list carries each rule's conditions and actions`.

### Task 2: Describer, popover (console)
**Files:** `src/app/api/automations.ts` (`RuleSummary.conditions: ConditionNode | null`, `actions: AutomationAction[]`),
`src/app/Automations/automationModel.ts` (describer), `src/app/Automations/RulePopover.tsx` (new),
`src/app/Automations/RulesTab.tsx`, `src/test/automations.ts` (fixtures get the fields);
tests `automationModel.test.ts`, `automationsPage.test.tsx`.
**Produces:** `type RuleLine = { text: string; depth: number }`;
`describeRule(r: Pick<RuleSummary,'event'|'conditions'|'actions'>, o: AutomationOptions | null): RuleLine[]`;
`RulePopover: FC<{ rule: RuleSummary; options: AutomationOptions | null; children: ReactElement }>`.
- [ ] Tests in `automationModel.test.ts`, `describe('describeRule')` (options `OPTIONS`):
  - `readsTheExampleRule`: event MESSAGE_RECEIVED, conditions `or[ and[number is n1, message.text is Hi], number is n2 ]`,
    action START_CHATBOT ifRunning SKIP with a chatbot in options named "Lead qualifier" → texts
    `["When a message comes in", "If any of these:", "all of these: Number is Main line, Message text is “Hi”",
    "Number is Sales line", "Then:", "1. Start “Lead qualifier” (if one is running: skip)"]`, depths `[0,0,1,1,0,1]`.
  - `noConditions`: conditions null (and an empty group) → second line "No conditions: every message that comes in runs this rule."
  - `nestedGroupsBecomeBullets`: `and[ or[ a, and[b, c] ] ]` → "If all of these:", "any of these:" (depth 1),
    a (depth 2), "all of these: b, c" (depth 2).
  - `negatedGroups`: `{combinator:'or', not:true}` → "None of these:"; `and` with not → "Not all of these:".
  - `missingChoiceShowsRawValue`: number `n9` → "Number is n9"; options null → raw ids too.
  - `noActions` → "Then: nothing yet".
- [ ] Tests in `automationsPage.test.tsx`: `hoverShowsTheRuleInWords` (hover the "Welcome" link → dialog/popover
  contains "When a message comes in" and the first action line); `focusOpensThePopover` (tab to the link → visible;
  Escape → gone).
- [ ] Run `npx vitest run src/app/Automations/automationModel.test.ts src/app/Automations/automationsPage.test.tsx --maxWorkers=4` → FAIL.
- [ ] Implement: a group whose children are all conditions renders inline ("all of these: a, b") when it is not the
  top group; otherwise a heading line plus children one depth deeper; Start chatbot appends
  " (if one is running: skip|replace)". Popover: PatternFly `Popover` `triggerAction="hover"`, `headerContent` = rule
  name, body a list indented by depth; RulesTab loads options with `useAutomationOptions()` and wraps the name link.
- [ ] Run the Automations folder tests, `npx tsc --noEmit && npm run lint` → PASS. Commit
  `feat(automations): hovering a rule shows it in words`.
