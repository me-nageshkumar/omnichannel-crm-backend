package me.nageshk.omnichannel.service.chatbot.run;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import org.springframework.stereotype.Component;

/** The task kinds something in this app answers; a node may only wait for one of them. */
@Component
public class TaskAnswerSources {

    private final Set<TaskKind> kinds = EnumSet.noneOf(TaskKind.class);

    public TaskAnswerSources(List<TaskAnswerSource> sources) {
        for (TaskAnswerSource s : sources) kinds.add(s.kind());
    }

    public Set<TaskKind> kinds() {
        return Set.copyOf(kinds);
    }
}
