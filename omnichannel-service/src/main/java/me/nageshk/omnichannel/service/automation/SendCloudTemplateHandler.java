package me.nageshk.omnichannel.service.automation;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import me.nageshk.omnichannel.common.api.NoDeviceException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.SubscriptionInactiveException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.api.WalletEmptyException;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.campaign.RecipientSkips;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.meta.api.CloudSendException;
import me.nageshk.omnichannel.meta.api.CloudTemplateInfo;
import me.nageshk.omnichannel.meta.api.CloudTemplates;
import me.nageshk.omnichannel.service.inbox.AutomationActor;
import me.nageshk.omnichannel.service.inbox.CloudTemplateRequest;
import me.nageshk.omnichannel.service.inbox.InboxSendService;
import org.springframework.stereotype.Component;

/**
 * "Send WhatsApp template": an approved Meta template to the event's chat on a Cloud API number, any time (spec
 * 2026-11-20 §5.6), so a rule can follow up after the 24-hour window. Blanks take the rule's values, else their fills.
 */
@Component
public class SendCloudTemplateHandler implements ActionHandler {

    private final WaWebChatRepository chats;
    private final ContactRepository contacts;
    private final RecipientSkips skips;
    private final CloudTemplates templates;
    private final InboxSendService sender;
    private final OmnichannelProperties props;

    public SendCloudTemplateHandler(WaWebChatRepository chats, ContactRepository contacts, RecipientSkips skips,
            CloudTemplates templates, InboxSendService sender, OmnichannelProperties props) {
        this.chats = chats;
        this.contacts = contacts;
        this.skips = skips;
        this.templates = templates;
        this.sender = sender;
        this.props = props;
    }

    @Override
    public AutomationActionType type() {
        return AutomationActionType.SEND_CLOUD_TEMPLATE;
    }

    @Override
    public ActionResult run(ActionContext ctx, AutomationAction action) {
        String chatId = ctx.data() == null || ctx.data().chat() == null ? null : ctx.data().chat().id();
        WaWebChat chat = chatId == null ? null : chats.findById(chatId).orElse(null);
        if (chat == null) return ActionResult.failed("No chat with this contact");
        if (doNotContact(ctx, chat)) return ActionResult.skipped("Contact is marked do-not-contact");

        CloudTemplateInfo template;
        try {
            template = templates.get(ctx.workspaceId(), action.cloudTemplateId());
        } catch (NotFoundException e) {
            return ActionResult.failed("The WhatsApp template no longer exists");
        }
        try {
            sender.sendCloudTemplate(new AutomationActor(ctx.ruleId(), ctx.ruleName()), ctx.workspaceId(), chat,
                    new CloudTemplateRequest(template.id(), action.values(), null), null)
                    .get(props.automation().sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return ActionResult.done("Sent WhatsApp template “" + template.name() + "”");
        } catch (ExecutionException e) {
            return failure(e.getCause() == null ? e : e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.interrupted("The app was stopping; the message may have gone");
        } catch (TimeoutException | RuntimeException e) {
            return failure(e);
        }
    }

    private boolean doNotContact(ActionContext ctx, WaWebChat chat) {
        Contact contact = chat.getContactId() == null ? null : contacts.findById(chat.getContactId()).orElse(null);
        if (contact != null && contact.getDoNotContact() != null) return true;
        String phone = contact == null ? null : contact.getPhone();
        return phone != null && skips.isDoNotContact(ctx.workspaceId(), phone);
    }

    private static ActionResult failure(Throwable cause) {
        if (cause instanceof SubscriptionInactiveException) return ActionResult.failed("The plan does not allow this");
        if (cause instanceof WalletEmptyException) return ActionResult.failed("The wallet balance is too low");
        if (cause instanceof NoDeviceException n) {
            return ActionResult.retryLater("The Cloud API number cannot send now: " + n.getMessage());
        }
        if (cause instanceof TimeoutException) {
            return ActionResult.interrupted("Meta did not answer in time; the message may have gone");
        }
        if (cause instanceof CloudSendException c) {
            return c.definitelyNotSent() ? ActionResult.failed("Meta did not send it: " + c.getMessage())
                    : ActionResult.interrupted("Meta did not answer in time; the message may have gone");
        }
        if (cause instanceof ValidationException v) {
            String channel = v.fields().get("cloudTemplateId");
            if (channel != null) return ActionResult.failed("The WhatsApp template could not be sent: " + channel);
            for (String field : v.fields().keySet()) {
                if (field.startsWith("values.")) {
                    return ActionResult.failed("Blank “" + field.substring("values.".length()) + "” has no value");
                }
            }
            return ActionResult.failed("The WhatsApp template could not be filled: " + v.fields());
        }
        return ActionResult.failed(String.valueOf(cause.getMessage()));
    }
}
