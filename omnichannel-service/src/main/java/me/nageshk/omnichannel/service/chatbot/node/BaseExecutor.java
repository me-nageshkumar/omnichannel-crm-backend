package me.nageshk.omnichannel.service.chatbot.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;

/** Shared plumbing: typed settings, the single {@code next} output, problem helpers. */
abstract class BaseExecutor implements NodeExecutor {

    protected final FlowParser parser;

    BaseExecutor(FlowParser parser) {
        this.parser = parser;
    }

    @Override
    public List<String> outputs(FlowNode node, ValidationContext ctx) {
        return List.of(Outputs.NEXT);
    }

    @Override
    public Set<TaskKind> waitsFor(FlowNode node, Flow flow) {
        return Set.of();
    }

    @Override
    public StepResult run(RunContext ctx, FlowNode node) {
        throw new UnsupportedOperationException(type() + " runs in a later task");
    }

    protected <T> T settings(FlowNode node, Class<T> type) {
        return parser.settings(node, type);
    }

    static final class Problems {
        final List<Problem> list = new ArrayList<>();
        private final FlowNode node;

        Problems(FlowNode node) {
            this.node = node;
        }

        void error(String field, String message) {
            list.add(Problem.error(node, "settings." + field, message));
        }

        void warning(String field, String message) {
            list.add(Problem.warning(node, "settings." + field, message));
        }

        /** Reports a missing (when required) or over-long value; true when it is fine. */
        boolean text(String field, String value, int max, boolean required) {
            if (value == null || value.isBlank()) {
                if (required) error(field, "is required");
                return !required;
            }
            if (value.length() > max) {
                error(field, "must be at most " + max + " characters");
                return false;
            }
            return true;
        }
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
