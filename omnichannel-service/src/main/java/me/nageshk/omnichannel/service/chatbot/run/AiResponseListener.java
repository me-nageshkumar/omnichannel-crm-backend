package me.nageshk.omnichannel.service.chatbot.run;

import me.nageshk.omnichannel.ai.api.AiTurnListener;
import me.nageshk.omnichannel.ai.api.TurnResult;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.db.chatbot.Waiting;
import me.nageshk.omnichannel.service.chatbot.node.TaskAnswer;
import org.springframework.stereotype.Component;

/**
 * The answer source for AI turns (spec §5.5): the AI worker hands every finished turn here, and the run waiting on
 * that call takes it. Delivery is at-least-once and may overlap, so this is idempotent: the run claim lets one
 * delivery through, and a later one finds nothing waiting. A turn can finish before the runner has saved the run as
 * waiting on it; then this throws, and the worker offers the result again a little later.
 */
@Component
public class AiResponseListener implements TaskAnswerSource, AiTurnListener {

    private final ChatbotRunService runs;
    private final ChatbotRunRepository runRepo;

    public AiResponseListener(ChatbotRunService runs, ChatbotRunRepository runRepo) {
        this.runs = runs;
        this.runRepo = runRepo;
    }

    @Override
    public TaskKind kind() {
        return TaskKind.AI_RESPONSE;
    }

    @Override
    public void turnFinished(TurnResult r) {
        if (r == null || r.callId() == null || r.runId() == null) return;
        if (runs.answer(TaskAnswer.ai(r.callId(), r))) return;
        ChatbotRun run = runRepo.findById(r.runId()).orElse(null);
        // ended, stopped, handed over or gone: nothing to answer
        if (run == null || run.getStatus() == null || !run.getStatus().active()) return;
        if (run.getStatus() == ChatbotRunStatus.WAITING) {
            Waiting w = run.getWaiting();
            if (w == null || w.kind() != TaskKind.AI_RESPONSE || !r.callId().equals(w.key())) return; // answered before
            // the runner saved the wait between the claim and this read
            if (runs.answer(TaskAnswer.ai(r.callId(), r))) return;
            throw new IllegalStateException("run " + run.getId() + " could not be claimed for AI call " + r.callId());
        }
        // RUNNING: either this very answer is being handled, or the runner has not saved the wait on it yet
        if (r.callId().equals(run.getLastAnswerKey())) return;
        throw new IllegalStateException("run not waiting yet: " + run.getId() + " for AI call " + r.callId());
    }
}
