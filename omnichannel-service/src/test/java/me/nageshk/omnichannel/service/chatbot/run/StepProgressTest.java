package me.nageshk.omnichannel.service.chatbot.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotStep;
import me.nageshk.omnichannel.db.chatbot.Waiting;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.node.StepResult;
import org.junit.jupiter.api.Test;

/** Steps carry what the conversation has collected (spec 2026-11-20 §6.3). */
class StepProgressTest {

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-20T10:05:00Z");
    private final FlowNode node = new FlowNode("n1", "Ask", NodeType.AI, null);
    private final Waiting waiting = Waiting.whatsApp("chat-1", T0, "Name?");

    private static ChatbotRun newRun() {
        ChatbotRun run = new ChatbotRun();
        run.setSteps(new ArrayList<>());
        return run;
    }

    private ChatbotStep only(ChatbotRun run) {
        assertThat(run.getSteps()).hasSize(1);
        return run.getSteps().get(0);
    }

    @Test
    void waitingStepTakesProgress() {
        ChatbotRun run = newRun();
        StepRecorder.waiting(run, node, T0, StepResult.wait(waiting, "asked").withProgress(Map.of("name", "Asha")));
        assertThat(only(run).progress()).isEqualTo(Map.of("name", "Asha"));
    }

    @Test
    void reAskKeepsEarlierProgressWhenNoneGiven() {
        ChatbotRun run = newRun();
        StepRecorder.waiting(run, node, T0, StepResult.wait(waiting, "asked").withProgress(Map.of("name", "Asha")));
        StepRecorder.waiting(run, node, T0, StepResult.wait(waiting, "asked again"));
        assertThat(only(run).progress()).isEqualTo(Map.of("name", "Asha"));
        assertThat(only(run).summary()).isEqualTo("asked again");
    }

    @Test
    void reAskTakesNewProgressWhenGiven() {
        ChatbotRun run = newRun();
        StepRecorder.waiting(run, node, T0, StepResult.wait(waiting, "a").withProgress(Map.of("name", "Asha")));
        StepRecorder.waiting(run, node, T0, StepResult.wait(waiting, "b").withProgress(Map.of("name", "Asha", "city", "Pune")));
        assertThat(only(run).progress()).isEqualTo(Map.of("name", "Asha", "city", "Pune"));
    }

    @Test
    void completingStepKeepsProgress() {
        ChatbotRun run = newRun();
        StepRecorder.waiting(run, node, T0, StepResult.wait(waiting, "asked").withProgress(Map.of("name", "Asha")));
        StepRecorder.complete(run, node, T0, T1, StepResult.next("next", Map.of(), "done"));
        assertThat(only(run).outcome()).isEqualTo(ChatbotStep.Outcome.DONE);
        assertThat(only(run).progress()).isEqualTo(Map.of("name", "Asha"));
    }

    @Test
    void failingStepKeepsProgress() {
        ChatbotRun run = newRun();
        StepRecorder.waiting(run, node, T0, StepResult.wait(waiting, "asked").withProgress(Map.of("name", "Asha")));
        StepRecorder.failed(run, node, T0, T1, "boom");
        assertThat(only(run).outcome()).isEqualTo(ChatbotStep.Outcome.FAILED);
        assertThat(only(run).progress()).isEqualTo(Map.of("name", "Asha"));
    }

    @Test
    void stepWithoutProgressHasNull() {
        ChatbotRun run = newRun();
        StepRecorder.complete(run, node, T0, T1, StepResult.next("next", Map.of(), "done"));
        assertThat(only(run).progress()).isNull();
    }
}
