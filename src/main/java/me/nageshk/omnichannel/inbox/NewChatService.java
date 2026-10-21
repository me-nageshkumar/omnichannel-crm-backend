package me.nageshk.omnichannel.inbox;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.ContactService;
import me.nageshk.omnichannel.contact.Phones;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
import org.springframework.stereotype.Service;

/**
 * Starts a conversation from the console with a phone number the number has not chatted with yet, or lands in the
 * chat that already exists for it. The first message goes through {@link InboxSendService}; a new chat is handed to
 * it unsaved, so a message the device refuses leaves no chat behind.
 */
@Service
@RequiredArgsConstructor
public class NewChatService {

    private static final String PHONE_MESSAGE = "must be 8 to 15 digits with the country code, e.g. 919812345678";

    private final PhoneNumberRepository numbers;
    private final InboxDevices devices;
    private final InboxScopeService scopes;
    private final WaWebChatRepository chats;
    private final ContactService contacts;
    private final ChatAssignmentService assignment;
    private final InboxSendService sender;
    private final Clock clock;

    /**
     * Callers check {@code inbox:send}. The phone and the device are checked before anything is written. An existing
     * chat is reopened if closed and claimed by the caller if unassigned, then written to like any reply; starting a
     * chat is an explicit claim, whatever {@code claimOnReply} says.
     *
     * @throws ValidationException if the phone is not 8 to 15 digits, or the message is invalid
     * @throws NotFoundException if the number is not in the workspace
     * @throws ChatTakenException if the chat is assigned to a user and not visible to the caller
     */
    public CompletableFuture<MessageView> start(AuthenticatedUser me, String workspaceId, String phoneNumberId,
            NewChatRequest req) {
        String phone = Phones.normalise(req == null ? null : req.phone());
        if (!Phones.isValid(phone)) throw new ValidationException("phone", PHONE_MESSAGE);
        PhoneNumber number = numbers.findByIdAndWorkspaceId(phoneNumberId, workspaceId)
                .orElseThrow(() -> new NotFoundException("phone number not found"));
        devices.requireReady(workspaceId, phoneNumberId);
        String remoteId = phone + "@c.us";
        SendRequest message = new SendRequest(req.text(), req.templateId(), req.values());

        WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(phoneNumberId, remoteId).orElse(null);
        if (chat != null) {
            if (chat.getAssignedUserId() != null && !scopes.scopeOf(me, workspaceId).covers(chat)) {
                throw new ChatTakenException();
            }
            if (chat.getStatus() == ChatStatus.CLOSED) chat = assignment.reopen(me, chat);
            if (chat.getAssignedUserId() == null) chat = assignment.assign(me, chat, me.userId(), null);
            return sender.send(me, workspaceId, chat, message);
        }

        Contact contact = contacts.upsertFromChat(workspaceId, number.getCustomerId(), remoteId, null, null, null);
        WaWebChat fresh = new WaWebChat();
        fresh.setCustomerId(number.getCustomerId());
        fresh.setWorkspaceId(workspaceId);
        fresh.setPhoneNumberId(phoneNumberId);
        fresh.setRemoteId(remoteId);
        fresh.setContactId(contact.getId());
        fresh.setStatus(ChatStatus.OPEN);
        fresh.setAssignedUserId(me.userId());
        fresh.setAssignedAt(clock.instant());
        return sender.send(me, workspaceId, fresh, message);
    }
}
