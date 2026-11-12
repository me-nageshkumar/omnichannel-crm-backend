package me.nageshk.omnichannel.service.chatbot.run;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import me.nageshk.omnichannel.common.api.NoDeviceException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.common.rpc.RpcException;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.chatbot.node.RunContext;
import me.nageshk.omnichannel.service.inbox.ChatbotActor;
import me.nageshk.omnichannel.service.inbox.InboxSendService;
import me.nageshk.omnichannel.service.inbox.MessageView;
import me.nageshk.omnichannel.service.inbox.SendRequest;
import org.springframework.stereotype.Component;

/**
 * Sends for a run (spec §5.7): as the chatbot, never to a do-not-contact contact, retried when the send never reached
 * the extension, failed for good when it may have gone.
 */
@Component
public class ChatbotSender {

    private final InboxSendService inbox;
    private final RunChat runChat;
    private final OmnichannelProperties props;

    public ChatbotSender(InboxSendService inbox, RunChat runChat, OmnichannelProperties props) {
        this.inbox = inbox;
        this.runChat = runChat;
        this.props = props;
    }

    public MessageView send(RunContext ctx, CloudMessage message) {
        WaWebChat chat = runChat.resolve(ctx);
        guard(ctx);
        try {
            return await(inbox.sendCloud(actor(ctx), ctx.workspaceId(), chat, message.withTo(toOf(chat, ctx.contact())), null, false));
        } catch (NoDeviceException | RpcException | ValidationException e) {
            throw map(e);
        }
    }

    public MessageView sendTemplate(RunContext ctx, String templateId, Map<String, String> values) {
        WaWebChat chat = runChat.resolve(ctx);
        guard(ctx);
        try {
            return await(inbox.send(actor(ctx), ctx.workspaceId(), chat, new SendRequest(null, templateId, values), null));
        } catch (NoDeviceException | RpcException | ValidationException e) {
            throw map(e);
        }
    }

    private static ChatbotActor actor(RunContext ctx) {
        return new ChatbotActor(ctx.run().getChatbotId(), ctx.run().getChatbotName(), ctx.run().getId());
    }

    private static void guard(RunContext ctx) {
        Contact c = ctx.contact();
        if (c != null && c.getDoNotContact() != null) throw new StopRun("do not contact");
    }

    private MessageView await(java.util.concurrent.CompletableFuture<MessageView> future) {
        try {
            return future.get(props.chatbot().sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw map(e.getCause() == null ? e : e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeFailed("the app was stopping; the message may have gone");
        } catch (TimeoutException e) {
            throw new NodeFailed("the extension did not answer in time; the message may have gone");
        } catch (NoDeviceException | RpcException e) {
            throw map(e);
        }
    }

    static RuntimeException map(Throwable cause) {
        if (cause instanceof NoDeviceException n) {
            return new RetryLater(NoDeviceException.WHATSAPP_NOT_READY.equals(n.code())
                    ? "WhatsApp Web is not ready on the number" : "the number's extension is offline or the number is disabled");
        }
        if (cause instanceof RpcException r) {
            if (RpcException.SEND_FAILED.equals(r.code())) return new RetryLater("the extension lost the connection");
            if (RpcException.WHATSAPP_NOT_READY.equals(r.code())) return new RetryLater("WhatsApp Web is not ready on the number");
            if (RpcException.DEVICE_DISCONNECTED.equals(r.code())) {
                return new NodeFailed("the extension lost the connection; the message may have gone");
            }
            return new NodeFailed("WhatsApp did not send it (" + r.code() + ")");
        }
        if (cause instanceof ValidationException v) return new NodeFailed("the message was refused: " + v.fields());
        if (cause instanceof RuntimeException r) return r;
        return new NodeFailed(String.valueOf(cause.getMessage()));
    }

    /** The Cloud {@code to}: the chat's digits, else the contact's phone, else the chat's id. */
    static String toOf(WaWebChat chat, Contact contact) {
        String remote = chat.getRemoteId() == null ? "" : chat.getRemoteId();
        if (remote.endsWith("@c.us")) return remote.substring(0, remote.indexOf('@'));
        if (contact != null && contact.getPhone() != null && !contact.getPhone().isBlank()) return contact.getPhone();
        return remote.isEmpty() ? "0" : remote;
    }
}
