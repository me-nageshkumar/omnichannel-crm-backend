package me.nageshk.omnichannel.service.cloud;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudSendResponse;
import me.nageshk.omnichannel.common.channel.whatsapp.message.MessagingProduct;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.inbox.InboxSendService;
import me.nageshk.omnichannel.service.inbox.MessageView;
import me.nageshk.omnichannel.service.inbox.NewChatService;
import org.springframework.stereotype.Service;

/**
 * The Cloud API's {@code POST /messages}, over the bridge (spec §5.6): a message keyed by {@code to}, or by a chat
 * when the console sends from an open thread. Answers in the Cloud response shape with our message id.
 */
@Service
public class CloudSendService {

    private final NewChatService newChats;
    private final InboxSendService sender;
    private final CloudMessageValidator validator;
    private final CloudMediaService media;
    private final ContactRepository contacts;

    public CloudSendService(NewChatService newChats, InboxSendService sender, CloudMessageValidator validator,
            CloudMediaService media, ContactRepository contacts) {
        this.newChats = newChats;
        this.sender = sender;
        this.validator = validator;
        this.media = media;
        this.contacts = contacts;
    }

    /** Sends to the phone in {@code to} through the number; a bridge number takes {@code whatsapp-web} only. */
    public CompletableFuture<CloudSendResponse> send(AuthenticatedUser me, String workspaceId, String phoneNumberId,
            CloudMessage message) {
        requireBridgeProduct(message);
        CloudMessage valid = validator.validate(workspaceId, message, media::mimeOf, true);
        return newChats.startCloud(me, workspaceId, phoneNumberId, valid).thenApply(view -> response(valid, view));
    }

    /** Sends to an open chat; {@code to} is the chat's phone when it has one, else its hidden id. */
    public CompletableFuture<CloudSendResponse> sendToChat(AuthenticatedUser me, String workspaceId, WaWebChat chat,
            CloudMessage message) {
        requireBridgeProduct(message);
        CloudMessage withTo = message.withTo(toOf(chat));
        CloudMessage valid = validator.validate(workspaceId, withTo, media::mimeOf, false);
        return sender.sendCloud(me, workspaceId, chat, valid, null, false).thenApply(view -> response(valid, view));
    }

    /** The composer's preview is validated the chat-keyed way: {@code to} is not part of it. */
    public CloudMessage validateForPreview(String workspaceId, CloudMessage message) {
        requireBridgeProduct(message);
        return validator.validate(workspaceId, message.withTo("preview"), media::mimeOf, false);
    }

    private static void requireBridgeProduct(CloudMessage message) {
        if (!MessagingProduct.WHATSAPP_WEB.equals(message.messagingProduct())) {
            throw new ValidationException("messaging_product", "channel not available");
        }
    }

    private String toOf(WaWebChat chat) {
        String remote = chat.getRemoteId() == null ? "" : chat.getRemoteId();
        if (remote.endsWith("@c.us")) return remote.substring(0, remote.indexOf('@'));
        if (chat.getContactId() != null) {
            String phone = contacts.findById(chat.getContactId()).map(Contact::getPhone).orElse(null);
            if (phone != null && !phone.isBlank()) return phone;
        }
        return remote;
    }

    private static CloudSendResponse response(CloudMessage m, MessageView view) {
        return new CloudSendResponse(MessagingProduct.WHATSAPP_WEB,
                List.of(new CloudSendResponse.Contact(m.to(), m.to())),
                List.of(new CloudSendResponse.Message(view.id())));
    }
}
