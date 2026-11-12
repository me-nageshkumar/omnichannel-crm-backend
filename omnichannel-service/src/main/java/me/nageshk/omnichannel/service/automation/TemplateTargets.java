package me.nageshk.omnichannel.service.automation;

import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.MessageTemplateRepository;
import me.nageshk.omnichannel.db.template.PlaceholderKind;
import me.nageshk.omnichannel.db.template.TemplatePlaceholder;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.db.template.TemplateType;
import org.springframework.stereotype.Component;

/** Whether a saved template can be sent with the given agent values, shared by rules and chatbot nodes. */
@Component
public class TemplateTargets {

    private final MessageTemplateRepository templates;

    public TemplateTargets(MessageTemplateRepository templates) {
        this.templates = templates;
    }

    /** Keys {@code templateId} and {@code values.<name>}; empty when sendable. */
    public Map<String, String> check(String workspaceId, String templateId, Map<String, String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        if (templateId == null || templateId.isBlank()) {
            out.put("templateId", "Choose a template");
            return out;
        }
        MessageTemplate t = templates.findByIdAndWorkspaceId(templateId, workspaceId).orElse(null);
        if (t == null) {
            out.put("templateId", "The template no longer exists");
        } else if (t.getStatus() == TemplateStatus.ARCHIVED) {
            out.put("templateId", "The template is archived");
        } else if (t.getType() == TemplateType.CONTACT_CARD) {
            out.put("templateId", "Contact card templates cannot be sent");
        } else if (t.getPlaceholders() != null) {
            for (TemplatePlaceholder p : t.getPlaceholders()) {
                if (p.getKind() != PlaceholderKind.AGENT || !p.isRequired()) continue;
                String v = values == null ? null : values.get(p.getName());
                if (v == null || v.isBlank()) {
                    out.put("values." + p.getName(),
                            (p.getLabel() == null || p.getLabel().isBlank() ? p.getName() : p.getLabel()) + " needs a value");
                }
            }
        }
        return out;
    }
}
