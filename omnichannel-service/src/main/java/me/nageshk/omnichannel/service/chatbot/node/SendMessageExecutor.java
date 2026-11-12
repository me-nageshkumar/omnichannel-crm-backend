package me.nageshk.omnichannel.service.chatbot.node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessages;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive;
import me.nageshk.omnichannel.common.channel.whatsapp.message.MessagingProduct;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowEdge;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import me.nageshk.omnichannel.service.chatbot.flow.settings.SendMessageSettings;
import me.nageshk.omnichannel.service.cloud.CloudMediaService;
import me.nageshk.omnichannel.service.cloud.CloudMessageValidator;
import org.springframework.stereotype.Component;

/**
 * Send message (spec §4.2): a Cloud API message object. With reply buttons or a list and at least one connected
 * reply output it waits for a WhatsApp reply and routes by the matched button or row.
 */
@Component
public class SendMessageExecutor extends BaseExecutor {

    private final CloudMessages cloud;
    private final CloudMessageValidator validator;
    private final CloudMediaService media;

    public SendMessageExecutor(FlowParser parser, CloudMessages cloud, CloudMessageValidator validator,
            CloudMediaService media) {
        super(parser);
        this.cloud = cloud;
        this.validator = validator;
        this.media = media;
    }

    @Override
    public NodeType type() {
        return NodeType.SEND_MESSAGE;
    }

    /** The node's message as a sendable Cloud message (product and a placeholder {@code to} filled in), or null. */
    CloudMessage message(FlowNode node) {
        SendMessageSettings s = settings(node, SendMessageSettings.class);
        if (s.message() == null || s.message().isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>(s.message());
        m.put("messaging_product", MessagingProduct.WHATSAPP_WEB);
        m.put("recipient_type", "individual");
        m.putIfAbsent("to", "0");
        return cloud.fromMap(m);
    }

    private static List<Interactive.Reply> replies(CloudMessage m) {
        return m == null || m.interactive() == null ? List.of() : CloudMessageValidator.replies(m.interactive());
    }

    @Override
    public List<String> outputs(FlowNode node, ValidationContext ctx) {
        CloudMessage m;
        try {
            m = message(node);
        } catch (ValidationException e) {
            return List.of(Outputs.NEXT);
        }
        List<Interactive.Reply> replies = replies(m);
        if (replies.isEmpty()) return List.of(Outputs.NEXT);
        List<String> out = new ArrayList<>();
        for (Interactive.Reply r : replies) if (r.id() != null) out.add(Outputs.reply(r.id()));
        out.add(Outputs.OTHER);
        return out;
    }

    /** True when the message has replies and the person connected at least one of them (spec §4.2). */
    boolean waits(FlowNode node, Flow flow) {
        try {
            if (replies(message(node)).isEmpty()) return false;
        } catch (ValidationException e) {
            return false;
        }
        for (FlowEdge e : flow.from(node.id())) if (e.output().startsWith("reply:")) return true;
        return false;
    }

    @Override
    public Set<TaskKind> waitsFor(FlowNode node, Flow flow) {
        return waits(node, flow) ? Set.of(TaskKind.WHATSAPP_REPLY) : Set.of();
    }

    @Override
    public List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx) {
        Problems p = new Problems(node);
        CloudMessage m;
        try {
            m = message(node);
        } catch (ValidationException e) {
            e.fields().forEach((k, v) -> p.error("message", v));
            return p.list;
        }
        if (m == null) {
            p.error("message", "is required");
            return p.list;
        }
        try {
            validator.validate(ctx.workspaceId(), m, media::mimeOf, false);
        } catch (ValidationException e) {
            e.fields().forEach((k, v) -> p.error("message." + k, v));
        }
        if (!replies(m).isEmpty() && !waits(node, flow)) {
            p.warning("message", "no button is connected, so the chatbot would not wait for the reply");
        }
        return p.list;
    }
}
