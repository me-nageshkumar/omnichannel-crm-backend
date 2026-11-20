# Final fix report (lead-owner-report backend)

Commands (one build at a time):
`./mvnw -q -pl omnichannel-app -am test -Dtest=ChatbotAiConversationIT#finishNullsAndBlanksNeverCountAsGiven -Dsurefire.failIfNoSpecifiedTests=false`
`./mvnw -q -pl omnichannel-app -am test -Dtest='ChatbotReportApiIT,ChatbotAiConversationIT' -Dsurefire.failIfNoSpecifiedTests=false`
`./mvnw -q -pl omnichannel-ai -am test`

## 1. Finish nulls counted as given
- Changed: AiConversationFlow.onAi merges finish fields only when non-null and not a blank string; ChatbotReportService
  `givenKeys(...)` (shared by report aggregation and the runs `given` filter) counts only entries whose value is not null and not "".
- Tests: ChatbotAiConversationIT.finishNullsAndBlanksNeverCountAsGiven; ChatbotReportApiIT.nullAndEmptyProgressValuesAreNotGiven.
- RED (IT): expected {"city"="Indore","name"="Asha"} but was {"city"=null,"course"="","name"="Asha"}.
  RED (report IT, with the value filter temporarily disabled): byCount[1] expected 1 but was 0.
- GREEN: ChatbotAiConversationIT 27 tests, ChatbotReportApiIT 18 tests, 0 failures.

## 2. `active` param on report/runs
- `GET .../report/runs?active=true`: runs whose status is RUNNING or WAITING (default false; combinable with from/to,
  phoneNumberId, nodeId, reached, chat, given). `active=true` with `status` -> 400, fields.active.
- Tests: ChatbotReportApiIT.activeListsRunningAndWaitingNewestFirst; runsListMatchesReportCounts now uses active=true.
- RED: activeLists...: expected 2 but was 3; runsListMatchesReportCounts:255 expected 2 but was 10 (param ignored). GREEN: as above.

## 3. Last step definition
- Steps always carry nodeId (StepRecorder builds every ChatbotStep from node.id()), so the two definitions cannot differ.
  `build` now takes the last array element like the runs `$expr` (comment added). Test lastStepIsTheLastArrayElementInReportAndRuns
  (a seeded null-nodeId final step) passes before and after; it pins the aligned behaviour.

## 4. Docs
- Four limits added to docs/superpowers/known-limits.md ("Lead owners and chatbot report").

## AI module: `./mvnw -q -pl omnichannel-ai -am test`: 133 tests, 0 failures.
