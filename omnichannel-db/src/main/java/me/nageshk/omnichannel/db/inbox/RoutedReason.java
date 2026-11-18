package me.nageshk.omnichannel.db.inbox;

/** Why a {@code ROUTED} action assigned the way it did, when it was not the plain routing setting. */
public enum RoutedReason {
    /** A returning customer went back to the agent who last handled them (design §3.3). */
    STICKY,
    /** The assignee handed the chat back to the team; routing ran again without them (§3.6). */
    HANDED_BACK,
    /** The number's owning team changed; the chat was re-routed under the new team (§3.1). */
    OWNER_CHANGED,
    /** The assignee left the team; the chat was re-routed without them (§3.1). */
    MEMBER_REMOVED,
    /** A history fetch created the chat and found it waiting on us. */
    FETCHED,
    /** An automation rule assigned the chat; the action's {@code reasonDetail} is the rule's name. */
    AUTOMATION,
    /** A chatbot's hand-over node used the number's routing; {@code reasonDetail} is the chatbot's name. */
    CHATBOT_HANDOVER,
    /** A chatbot ended and the chat went to the team; {@code reasonDetail} is the chatbot's name. */
    CHATBOT_ENDED
}
