package me.nageshk.omnichannel.service.automation;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import me.nageshk.omnichannel.common.api.NoDeviceException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.common.rpc.RpcException;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.campaign.RecipientSkips;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.TemplatePlaceholder;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.service.inbox.AutomationActor;
import me.nageshk.omnichannel.service.inbox.InboxSendService;
import me.nageshk.omnichannel.service.inbox.SendRequest;
import me.nageshk.omnichannel.service.template.TemplateService;
import org.springframework.stereotype.Component;

/**
 * Sends one of the brand's templates to the event's chat as {@code "Automation: <rule name>"}. It runs at any hour,
 * never messages a do-not-contact contact, and never claims or reopens the chat (the inbox sender sees to that for an
 * {@link AutomationActor}). A send that never reached the extension (no device, WhatsApp Web not ready, the frame
 * not handed over) is retried by the worker; one the extension got but did not confirm (no answer in time, a
 * disconnect during the call, the app stopping) may have gone, so it is interrupted and never sent again. WhatsApp
 * refusing is permanent.
 */
@Component
public class SendTemplateHandler implements ActionHandler {

    private final WaWebChatRepository chats;
    private final ContactRepository contacts;
    private final RecipientSkips skips;
    private final TemplateService templates;
    private final InboxSendService sender;
    private final OmnichannelProperties props;

    public SendTemplateHandler(WaWebChatRepository chats, ContactRepository contacts, RecipientSkips skips,
            TemplateService templates, InboxSendService sender, OmnichannelProperties props) {
        this.chats = chats;
        this.contacts = contacts;
        this.skips = skips;
        this.templates = templates;
        this.sender = sender;
        this.props = props;
    }

    @Override
    public AutomationActionType type() {
        return AutomationActionType.SEND_TEMPLATE;
    }

    @Override
    public ActionResult run(ActionContext ctx, AutomationAction action) {
        String chatId = ctx.data() == null || ctx.data().chat() == null ? null : ctx.data().chat().id();
        WaWebChat chat = chatId == null ? null : chats.findById(chatId).orElse(null);
        if (chat == null) return ActionResult.failed("No chat with this contact");

        if (doNotContact(ctx, chat)) return ActionResult.skipped("Contact is marked do-not-contact");

        MessageTemplate template;
        try {
            template = templates.require(ctx.workspaceId(), action.templateId());
        } catch (NotFoundException e) {
            return ActionResult.failed("The template no longer exists");
        }
        if (template.getStatus() == TemplateStatus.ARCHIVED) return ActionResult.failed("The template is archived");

        try {
            sender.send(new AutomationActor(ctx.ruleId(), ctx.ruleName()), ctx.workspaceId(), chat,
                    new SendRequest(null, template.getId(), action.values()), null)
                    .get(props.automation().sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return ActionResult.done("Sent “" + template.getName() + "”");
        } catch (ExecutionException e) {
            return failure(e.getCause() == null ? e : e.getCause(), template);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.interrupted("The app was stopping; the message may have gone");
        } catch (TimeoutException | RuntimeException e) {
            return failure(e, template);
        }
    }

    /** The chat's contact is marked, or the brand has a marked contact with its phone. */
    private boolean doNotContact(ActionContext ctx, WaWebChat chat) {
        Contact contact = chat.getContactId() == null ? null : contacts.findById(chat.getContactId()).orElse(null);
        if (contact != null && contact.getDoNotContact() != null) return true;
        String phone = contact == null ? null : contact.getPhone();
        if (phone != null && skips.isDoNotContact(ctx.workspaceId(), phone)) return true;
        String eventPhone = ctx.data().contact() == null ? null : ctx.data().contact().phone();
        return eventPhone != null && !eventPhone.equals(phone) && skips.isDoNotContact(ctx.workspaceId(), eventPhone);
    }

    private static ActionResult failure(Throwable cause, MessageTemplate template) {
        if (cause instanceof NoDeviceException n) {
            return ActionResult.retryLater(NoDeviceException.WHATSAPP_NOT_READY.equals(n.code())
                    ? "WhatsApp Web is not ready on the number"
                    : "The number's extension is offline or the number is disabled");
        }
        if (cause instanceof TimeoutException) {
            return ActionResult.interrupted("The extension did not answer in time; the message may have gone");
        }
        if (cause instanceof RpcException r) {
            // SEND_FAILED: the frame could not be handed to the session, so nothing left the server
            if (RpcException.SEND_FAILED.equals(r.code())) {
                return ActionResult.retryLater("The extension lost the connection");
            }
            // WHATSAPP_NOT_READY: the page refused before sending, so it is as transient as a not-ready number
            if (RpcException.WHATSAPP_NOT_READY.equals(r.code())) {
                return ActionResult.retryLater("WhatsApp Web is not ready on the number");
            }
            // DEVICE_DISCONNECTED: the frame had left and the device dropped before answering
            if (RpcException.DEVICE_DISCONNECTED.equals(r.code())) {
                return ActionResult.interrupted("The extension lost the connection; the message may have gone");
            }
            return ActionResult.failed("WhatsApp did not send it (" + r.code() + ")");
        }
        if (cause instanceof ValidationException v) {
            for (String field : v.fields().keySet()) {
                for (TemplatePlaceholder p : template.getPlaceholders()) {
                    if (field.equals(p.getName())) {
                        String label = p.getLabel() == null || p.getLabel().isBlank() ? p.getName() : p.getLabel();
                        return ActionResult.failed("Placeholder “" + label + "” has no value");
                    }
                }
            }
            return ActionResult.failed("The template could not be filled: " + v.fields());
        }
        if (cause instanceof NotFoundException) return ActionResult.failed("The template or contact no longer exists");
        throw cause instanceof RuntimeException r ? r : new IllegalStateException(cause);
    }
}
