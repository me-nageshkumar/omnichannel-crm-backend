package me.nageshk.omnichannel.common.billing;

/** The things a plan can cap. A plan that does not name a key leaves it unlimited. */
public enum LimitKey {
    USERS(LimitScope.CUSTOMER, "Users"),
    BRANDS(LimitScope.CUSTOMER, "Brands"),
    PHONE_NUMBERS(LimitScope.CUSTOMER, "WhatsApp numbers"),
    AUTOMATION_RULES(LimitScope.CUSTOMER, "Automation rules"),
    CHATBOTS(LimitScope.CUSTOMER, "Chatbots"),
    AI_PROFILES(LimitScope.CUSTOMER, "AI profiles"),
    CHATBOT_NODES(LimitScope.CHATBOT, "Nodes per chatbot"),
    CHATBOT_AI_NODES(LimitScope.CHATBOT, "AI nodes per chatbot"),
    CRM_PIPELINES(LimitScope.BRAND, "CRM pipelines per brand"),
    CRM_CUSTOM_FIELDS(LimitScope.BRAND, "CRM custom fields per record type");

    private final LimitScope scope;
    private final String label;

    LimitKey(LimitScope scope, String label) {
        this.scope = scope;
        this.label = label;
    }

    public LimitScope scope() { return scope; }
    public String label() { return label; }
}
