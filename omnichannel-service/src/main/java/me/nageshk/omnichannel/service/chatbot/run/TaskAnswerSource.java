package me.nageshk.omnichannel.service.chatbot.run;

import me.nageshk.omnichannel.db.chatbot.TaskKind;

/** A component that turns some event into a task answer of one kind (spec §5.3). */
@FunctionalInterface
public interface TaskAnswerSource {
    TaskKind kind();
}
