package me.nageshk.omnichannel.service.chatbot;

import java.util.List;
import me.nageshk.omnichannel.db.chatbot.ChatbotRepository;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactLabelRepository;
import me.nageshk.omnichannel.service.automation.AutomationCrm;
import me.nageshk.omnichannel.service.automation.AutomationOptionsService;
import me.nageshk.omnichannel.service.automation.AutomationRef;
import me.nageshk.omnichannel.service.automation.OptionsView;
import org.springframework.stereotype.Service;

/** What the chatbot editor's forms pick from: the automations' options plus attributes, labels and chatbots. */
@Service
public class ChatbotOptionsService {

    public record AttributeOption(String id, String name, String key) {
    }

    public record ChatbotOptions(List<OptionsView.NumberOption> numbers, List<OptionsView.AgentOption> agents,
            List<AutomationRef> teams, List<OptionsView.TemplateOption> templates, AutomationCrm.CrmOptions crm,
            List<AttributeOption> attributes, List<AutomationRef> labels, List<ChatbotViews.ChatbotRef> chatbots) {
    }

    private final AutomationOptionsService automation;
    private final ContactAttributeRepository attributes;
    private final ContactLabelRepository labels;
    private final ChatbotRepository chatbots;

    public ChatbotOptionsService(AutomationOptionsService automation, ContactAttributeRepository attributes,
            ContactLabelRepository labels, ChatbotRepository chatbots) {
        this.automation = automation;
        this.attributes = attributes;
        this.labels = labels;
        this.chatbots = chatbots;
    }

    public ChatbotOptions options(String workspaceId) {
        OptionsView o = automation.options(workspaceId);
        return new ChatbotOptions(o.numbers(), o.agents(), o.teams(), o.templates(), o.crm(),
                attributes.findByWorkspaceIdOrderByNameKey(workspaceId).stream()
                        .map(a -> new AttributeOption(a.getId(), a.getName(), a.getKey())).toList(),
                labels.findByWorkspaceIdOrderByNameKey(workspaceId).stream()
                        .map(l -> new AutomationRef(l.getId(), l.getName())).toList(),
                chatbots.findAllByWorkspaceIdOrderByNameAsc(workspaceId).stream()
                        .map(c -> new ChatbotViews.ChatbotRef(c.getId(), c.getName(), c.getStatus())).toList());
    }
}
