package me.nageshk.omnichannel.ai.api;

import java.util.List;

/** Implemented by the app: which chatbots use a profile (so a profile in use cannot be deleted). */
public interface AiProfileUsage {

    List<String> chatbotsUsing(String workspaceId, String profileId);
}
