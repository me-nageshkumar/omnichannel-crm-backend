# Rule summary on hover in the automations list

Short design (a small change), agreed with the user on 2026-11-14. Calls the user made are marked **(the user's)**,
mine **(my call)**.

## Intent
The rules list shows a rule's name, event and a one-line action summary; to know what a rule really does you have to
open it. The user wants the list to say it: hovering a rule shows the rule in plain words, as the reference project
does for saved contact filters.

## Goals, non-goals, anti-goals
- Goal: hovering (or keyboard-focusing) a rule's name shows the event, the condition tree and the actions, in words.
- Non-goals: editing from the popover; a top-level numbers setting on rules (discussed and dropped: the user keeps the
  existing design, where the Number condition can be combined with others **(the user's)**).
- Anti-goal: wording that differs from the editor, the test page and the run logs.

## Approaches
- **A. Our own describer (picked, the user's).** A function walks our condition tree with the wording the console
  already has (`conditionText`, `actionSummary`, `eventLabel`); no new dependency.
- B. `react-querybuilder`'s `formatQuery(…, "natural_language")` as the reference does: a dependency for one function,
  and its own sentence style unless every operator and value is mapped, which is most of A's work anyway.

## Design
- **Server:** `RuleSummaryView` (the list's `GET /automations/rules` items) gains `conditions` (the stored
  `ConditionNode`, null when none) and `actions` (the stored actions), so hovering needs no extra request **(my call)**.
- **Console:** `describeRule(rule, options)` in `automationModel.ts` returns lines:
  - `When <event label>` (e.g. "When a message comes in").
  - Conditions: none → "Every event runs it" (wording of the editor's empty state per event); one top-level group →
    "If all of these:" / "If any of these:", one bullet per child; a nested group becomes a bullet
    "all of these: a, b" when its children are all conditions, else nested bullets; a group with `not` reads
    "none of these" for "any" and "not all of these" for "all" **(my call)**; each condition via `conditionText`.
  - "Then:" and numbered actions via `actionSummary`, plus Start chatbot's "(if one is running: skip|replace)".
- **Where:** a PatternFly `Popover` with `triggerAction="hover"` on the rule's name link in `RulesTab.tsx`; keyboard
  focus opens it too; header is the rule name, body the lines above **(my call: the name, not the whole row, so the
  row's buttons stay usable and the popover has one clear anchor)**. While the options are loading, the popover shows
  the conditions with raw values rather than waiting.
- **Example** ("Hi starts Lead qualifier" as now configured):
  ```
  When a message comes in
  If any of these:
    • all of these: Number is Main line, Message text is “Hi”
    • Number is Sales line
  Then:
    1. Start “Lead qualifier” (if one is running: skip)
  ```

## Constraints
Project rules §1: worktree `.worktrees/rule-hover-summary` in both repos, test first, Prettier on touched files, a fresh
reviewer, one fix pass, full verify before a merge; merge, push and deploy only when asked.

## Left to the builder
Popover width and placement, bullet markup, exact empty-state wording per event.
