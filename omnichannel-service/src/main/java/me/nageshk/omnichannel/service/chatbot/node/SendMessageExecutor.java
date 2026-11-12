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
import me.nageshk.omnichannel.service.chatbot.run.ChatbotSender;
import me.nageshk.omnichannel.service.cloud.CloudMediaService;
import me.nageshk.omnichannel.service.cloud.CloudMessageValidator;
import me.nageshk.omnichannel.db.channel.ChannelWebhookEvent;
import me.nageshk.omnichannel.db.chatbot.Waiting;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
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
    private final MongoTemplate mongo;

    public SendMessageExecutor(FlowParser parser, CloudMessages cloud, CloudMessageValidator validator,
            CloudMediaService media, MongoTemplate mongo) {
        super(parser);
        this.cloud = cloud;
        this.validator = validator;
        this.media = media;
        this.mongo = mongo;
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

    // ---- running ----

    /** The message with every string rendered against the run's variables. */
    CloudMessage rendered(RunContext ctx, FlowNode node) {
        SendMessageSettings s = settings(node, SendMessageSettings.class);
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : s.message().entrySet()) m.put(e.getKey(), renderDeep(ctx, e.getValue()));
        m.put("messaging_product", MessagingProduct.WHATSAPP_WEB);
        m.put("recipient_type", "individual");
        m.putIfAbsent("to", "0");
        return cloud.fromMap(m);
    }

    @SuppressWarnings("unchecked")
    private static Object renderDeep(RunContext ctx, Object o) {
        if (o instanceof String str) return ctx.render(str);
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), renderDeep(ctx, e.getValue()));
            return out;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object x : l) out.add(renderDeep(ctx, x));
            return out;
        }
        return o;
    }

    private static String summaryOf(CloudMessage m) {
        if (m.text() != null) return m.text().body();
        if (m.interactive() != null && m.interactive().body() != null) return m.interactive().body().text();
        if (m.media() != null && m.media().caption() != null) return m.media().caption();
        return m.type();
    }

    @Override
    public StepResult run(RunContext ctx, FlowNode node) {
        CloudMessage m = rendered(ctx, node);
        ctx.sender(ChatbotSender.class).send(ctx, m);
        String summary = summaryOf(m);
        if (waits(node, ctx.flow())) {
            return StepResult.wait(Waiting.whatsApp(ctx.chat().getId(), ctx.now(), summary), "Sent “" + summary + "”, waiting for a reply");
        }
        return StepResult.next(Outputs.NEXT, null, "Sent “" + summary + "”");
    }

    @Override
    public StepResult onAnswer(RunContext ctx, FlowNode node, TaskAnswer answer) {
        CloudMessage m = rendered(ctx, node);
        WaWebMessage reply = answer.message();
        String text = reply == null ? null : reply.getBody();
        Interactive.Reply matched = match(m, reply);
        if (matched != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", matched.id());
            r.put("title", matched.title());
            r.put("text", text);
            result.put("reply", r);
            return StepResult.next(Outputs.reply(matched.id()), result, "Reply “" + matched.title() + "”");
        }
        ChatbotSender sender = ctx.sender(ChatbotSender.class);
        int retries = ctx.settings().invalidReplyRetries();
        if (ctx.run().getInvalidReplies() < retries) {
            ctx.run().setInvalidReplies(ctx.run().getInvalidReplies() + 1);
            sender.send(ctx, textMessage(ctx.settings().retryMessage()));
            sender.send(ctx, m);
            return StepResult.wait(Waiting.whatsApp(ctx.chat().getId(), ctx.now(), summaryOf(m)),
                    "Reply did not match, asked again (" + ctx.run().getInvalidReplies() + "/" + retries + ")");
        }
        if (ctx.settings().giveUpMessage() != null && !ctx.settings().giveUpMessage().isBlank()) {
            sender.send(ctx, textMessage(ctx.settings().giveUpMessage()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", null);
        r.put("title", null);
        r.put("text", text);
        result.put("reply", r);
        return StepResult.next(Outputs.OTHER, result, "No matching reply");
    }

    /** The button or row the reply picked: the stored webhook event's reply id when it has one, else the title. */
    Interactive.Reply match(CloudMessage m, WaWebMessage reply) {
        if (reply == null || m.interactive() == null) return null;
        Map<String, Object> event = reply.getWaMessageId() == null ? null : webhookMessage(reply.getWaMessageId());
        return ReplyMatcher.match(m.interactive(), reply, event).orElse(null);
    }

    /** The Cloud-shaped {@code messages[0]} of the stored webhook event for a message id, or null. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> webhookMessage(String waMessageId) {
        ChannelWebhookEvent e = mongo.findOne(new Query(Criteria.where("eventKey").is("message:" + waMessageId)),
                ChannelWebhookEvent.class);
        if (e == null || e.getEvent() == null) return null;
        try {
            Object entry = ((List<Object>) e.getEvent().get("entry")).get(0);
            Object change = ((List<Object>) ((Map<String, Object>) entry).get("changes")).get(0);
            Object value = ((Map<String, Object>) change).get("value");
            Object messages = ((Map<String, Object>) value).get("messages");
            return (Map<String, Object>) ((List<Object>) messages).get(0);
        } catch (RuntimeException notTheShape) {
            return null;
        }
    }

    public static CloudMessage textMessage(String body) {
        return new CloudMessage(MessagingProduct.WHATSAPP_WEB, "individual", "0", CloudMessage.TEXT,
                new me.nageshk.omnichannel.common.channel.whatsapp.message.Text(false, body), null, null);
    }
}
