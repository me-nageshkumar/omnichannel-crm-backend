package me.nageshk.omnichannel.service.chatbot.node;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactLabelRepository;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import me.nageshk.omnichannel.service.chatbot.run.NodeFailed;
import me.nageshk.omnichannel.service.chatbot.run.RunVariables;
import me.nageshk.omnichannel.service.contact.ContactService;
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
    private final ContactService contacts;
    private final RunVariables variables;

    public UpdateContactExecutor(FlowParser parser, ContactAttributeRepository attributes, ContactLabelRepository labels,
            ContactService contacts, RunVariables variables) {
        super(parser);
        this.attributes = attributes;
        this.labels = labels;
        this.contacts = contacts;
        this.variables = variables;
    }

    @Override
    public StepResult run(RunContext ctx, FlowNode node) {
        UpdateContactSettings s = settings(node, UpdateContactSettings.class);
        Contact c = ctx.contact();
        if (c == null) throw new NodeFailed("the contact no longer exists");
        String ws = ctx.workspaceId();
        Map<String, String> values = new LinkedHashMap<>();
        if (s.setAttributes() != null) s.setAttributes().forEach((id, v) -> values.put(id, ctx.render(v)));
        if (s.clearAttributes() != null) for (String id : s.clearAttributes()) values.put(id, "");
        int changes = 0;
        if (!values.isEmpty()) {
            c = contacts.update(ws, c.getId(), null, values);
            changes += values.size();
        }
        if (s.addLabels() != null) for (String id : s.addLabels()) { c = contacts.addLabel(ws, c.getId(), id); changes++; }
        if (s.removeLabels() != null) for (String id : s.removeLabels()) { c = contacts.removeLabel(ws, c.getId(), id); changes++; }
        if (s.nameIfBlank() != null && !s.nameIfBlank().isBlank() && (c.getName() == null || c.getName().isBlank())) {
            contacts.nameIfBlank(ws, c.getId(), ctx.render(s.nameIfBlank()));
            c = contacts.require(ws, c.getId());
            changes++;
        }
        ctx.contact(c);
        variables.refreshContact(ctx.vars(), c, attributes.findByWorkspaceIdOrderByNameKey(ws), labels.findByWorkspaceIdOrderByNameKey(ws));
        return StepResult.next(Outputs.NEXT, null, "Updated the contact (" + changes + " change" + (changes == 1 ? "" : "s") + ")");
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
