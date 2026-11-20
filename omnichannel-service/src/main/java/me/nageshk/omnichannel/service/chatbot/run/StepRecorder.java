package me.nageshk.omnichannel.service.chatbot.run;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotStep;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.node.StepResult;

/**
 * Writes a run's steps. A step's progress is the result's progress when it has one, else what the same WAITING
 * step already had, else null.
 */
final class StepRecorder {

    private StepRecorder() {
    }

    private static boolean waitingOn(List<ChatbotStep> steps, FlowNode node) {
        int last = steps.size() - 1;
        return last >= 0 && steps.get(last).outcome() == ChatbotStep.Outcome.WAITING && steps.get(last).nodeId().equals(node.id());
    }

    static void waiting(ChatbotRun run, FlowNode node, Instant startedAt, StepResult r) {
        List<ChatbotStep> steps = run.getSteps();
        if (waitingOn(steps, node)) {
            // a re-ask: the same wait goes on
            int last = steps.size() - 1;
            ChatbotStep s = steps.get(last);
            steps.set(last, new ChatbotStep(s.index(), s.nodeId(), s.nodeName(), s.type(), s.startedAt(), null, s.outcome(), null,
                    r.summary() != null ? r.summary() : s.summary(), null, null, r.waiting(),
                    r.progress() != null ? r.progress() : s.progress()));
            return;
        }
        steps.add(new ChatbotStep(steps.size(), node.id(), node.name(), node.type().code(), startedAt, null,
                ChatbotStep.Outcome.WAITING, null, r.summary(), null, null, r.waiting(), r.progress()));
    }

    static void complete(ChatbotRun run, FlowNode node, Instant startedAt, Instant now, StepResult r) {
        List<ChatbotStep> steps = run.getSteps();
        if (waitingOn(steps, node)) {
            int last = steps.size() - 1;
            ChatbotStep s = steps.get(last);
            steps.set(last, new ChatbotStep(s.index(), s.nodeId(), s.nodeName(), s.type(), s.startedAt(), now,
                    ChatbotStep.Outcome.DONE, r.output(), r.summary() != null ? r.summary() : s.summary(), null, r.result(),
                    s.waitedFor(), r.progress() != null ? r.progress() : s.progress()));
            return;
        }
        steps.add(new ChatbotStep(steps.size(), node.id(), node.name(), node.type().code(), startedAt, now,
                ChatbotStep.Outcome.DONE, r.output(), r.summary(), null, r.result(), null, r.progress()));
    }

    static void failed(ChatbotRun run, FlowNode node, Instant startedAt, Instant now, String error) {
        List<ChatbotStep> steps = run.getSteps();
        if (waitingOn(steps, node)) {
            int last = steps.size() - 1;
            ChatbotStep s = steps.get(last);
            steps.set(last, new ChatbotStep(s.index(), s.nodeId(), s.nodeName(), s.type(), s.startedAt(), now,
                    ChatbotStep.Outcome.FAILED, null, s.summary(), error, null, s.waitedFor(), s.progress()));
            return;
        }
        steps.add(new ChatbotStep(steps.size(), node.id(), node.name(), node.type().code(), startedAt, now,
                ChatbotStep.Outcome.FAILED, null, null, error, null, null, null));
    }
}
