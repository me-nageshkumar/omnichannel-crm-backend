package me.nageshk.omnichannel.service.chatbot.flow.settings;

import me.nageshk.omnichannel.db.automation.AssignMode;
import me.nageshk.omnichannel.db.automation.WhenAway;

/** Exactly the automation action's choices (spec §4.2). */
public record AssignChatSettings(AssignMode mode, String userId, String teamId, WhenAway whenAway) {
}
