package me.nageshk.omnichannel.service.automation;

import java.util.List;
import me.nageshk.omnichannel.db.template.PlaceholderKind;

/** What the rule editor offers: the brand's numbers, agents, teams, templates and CRM. */
public record OptionsView(List<NumberOption> numbers, List<AgentOption> agents, List<AutomationRef> teams,
        List<TemplateOption> templates, AutomationCrm.CrmOptions crm, List<ChatbotOption> chatbots,
        List<CloudTemplateOption> cloudTemplates) {

    /** A sendable WhatsApp (Meta) template, the Cloud numbers of its WABA, and its blanks. */
    public record CloudTemplateOption(String id, String name, String language, List<String> phoneNumberIds,
            List<me.nageshk.omnichannel.meta.api.CloudTemplateInfo.Variable> variables,
            java.util.Map<String, me.nageshk.omnichannel.meta.api.CloudTemplateInfo.Fill> fills) {}

    /** A chatbot the Start chatbot action may name; drafts are shown greyed. */
    public record ChatbotOption(String id, String name, me.nageshk.omnichannel.db.chatbot.ChatbotStatus status) {
    }

    public record NumberOption(String id, String number, String label, AutomationRef owningTeam) {
    }

    public record AgentOption(String id, String name, List<String> teamIds, boolean seesAllNumbers) {
    }

    public record PlaceholderOption(String name, PlaceholderKind kind, String label, boolean required,
            String fallback) {
    }

    public record TemplateOption(String id, String name, String type, List<PlaceholderOption> placeholders) {
    }
}
