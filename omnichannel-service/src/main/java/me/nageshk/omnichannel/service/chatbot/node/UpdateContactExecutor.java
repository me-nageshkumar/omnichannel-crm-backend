package me.nageshk.omnichannel.service.chatbot.node;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactLabelRepository;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.settings.UpdateContactSettings;
import org.springframework.stereotype.Component;

/** Update contact (spec §4.2): set or clear attributes, add or remove labels, set the name if blank. */
@Component
public class UpdateContactExecutor extends BaseExecutor {

    private final ContactAttributeRepository attributes;
    private final ContactLabelRepository labels;

    public UpdateContactExecutor(FlowParser parser, ContactAttributeRepository attributes, ContactLabelRepository labels) {
        super(parser);
        this.attributes = attributes;
        this.labels = labels;
    }

    @Override
    public NodeType type() {
        return NodeType.UPDATE_CONTACT;
    }

    @Override
    public List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx) {
        UpdateContactSettings s = settings(node, UpdateContactSettings.class);
        Problems p = new Problems(node);
        boolean any = false;
        if (s.setAttributes() != null) {
            for (Map.Entry<String, String> e : s.setAttributes().entrySet()) {
                any = true;
                if (attributes.findByIdAndWorkspaceId(e.getKey(), ctx.workspaceId()).isEmpty()) {
                    p.error("setAttributes." + e.getKey(), "the attribute no longer exists");
                }
                p.text("setAttributes." + e.getKey(), e.getValue(), 500, false);
            }
        }
        any |= ids(s.clearAttributes(), "clearAttributes", ctx, p, true);
        any |= ids(s.addLabels(), "addLabels", ctx, p, false);
        any |= ids(s.removeLabels(), "removeLabels", ctx, p, false);
        if (!blank(s.nameIfBlank())) {
            any = true;
            p.text("nameIfBlank", s.nameIfBlank(), 100, false);
        }
        if (!any) list(p).add(Problem.error(node, "settings", "choose at least one change"));
        return p.list;
    }

    private static List<Problem> list(Problems p) {
        return p.list;
    }

    private boolean ids(List<String> ids, String field, ValidationContext ctx, Problems p, boolean attribute) {
        if (ids == null || ids.isEmpty()) return false;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            boolean exists = !blank(id) && (attribute
                    ? attributes.findByIdAndWorkspaceId(id, ctx.workspaceId()).isPresent()
                    : labels.findByIdAndWorkspaceId(id, ctx.workspaceId()).isPresent());
            if (!exists) p.error(field + "[" + i + "]", attribute ? "the attribute no longer exists" : "the label no longer exists");
        }
        return true;
    }
}
