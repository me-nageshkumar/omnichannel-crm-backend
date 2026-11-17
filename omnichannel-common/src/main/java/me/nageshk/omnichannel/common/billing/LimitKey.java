package me.nageshk.omnichannel.common.billing;

/** The things a plan can cap. A plan that does not name a key leaves it unlimited. */
public enum LimitKey {
    USERS(LimitScope.CUSTOMER, "Users", "users"),
    BRANDS(LimitScope.CUSTOMER, "Brands", "brands"),
    PHONE_NUMBERS(LimitScope.CUSTOMER, "WhatsApp numbers", "WhatsApp numbers"),
    AUTOMATION_RULES(LimitScope.CUSTOMER, "Automation rules", "automation rules"),
    CHATBOTS(LimitScope.CUSTOMER, "Chatbots", "chatbots"),
    AI_PROFILES(LimitScope.CUSTOMER, "AI profiles", "AI profiles"),
    CHATBOT_NODES(LimitScope.CHATBOT, "Nodes per chatbot", "nodes in a chatbot"),
    CHATBOT_AI_NODES(LimitScope.CHATBOT, "AI nodes per chatbot", "AI nodes in a chatbot"),
    CRM_PIPELINES(LimitScope.BRAND, "CRM pipelines per brand", "pipelines in a brand"),
    CRM_CUSTOM_FIELDS(LimitScope.BRAND, "CRM custom fields per record type", "custom fields per record type");

    private final LimitScope scope;
    private final String label;
    private final String noun;

    LimitKey(LimitScope scope, String label, String noun) {
        this.scope = scope;
        this.label = label;
        this.noun = noun;
    }

    public LimitScope scope() { return scope; }
    public String label() { return label; }
    /** For a sentence: "Your plan allows 5 users". */
    public String noun() { return noun; }
}
