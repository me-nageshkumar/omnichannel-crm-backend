package me.nageshk.omnichannel.inbox;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.ContactRepository;
import me.nageshk.omnichannel.contact.Phones;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
import org.springframework.stereotype.Service;

/**
 * Starts a conversation from the console or a campaign with a phone number the number has not chatted with yet, or
 * lands in the chat that already exists for it. The first message goes through {@link InboxSendService}; a new chat
 * is handed to it unsaved, so a message the device refuses leaves no chat behind.
 */
@Service
@RequiredArgsConstructor
public class NewChatService {

    private final PhoneNumberRepository numbers;
    private final InboxDevices devices;
    private final InboxScopeService scopes;
    private final WaWebChatRepository chats;
    private final ContactRepository contactRepository;
    private final ChatAssignmentService assignment;
    private final InboxSendService sender;
    private final NumberLookupService lookups;
    private final Clock clock;

    /**
     * Callers check {@code inbox:send}. The phone and the device are checked before anything is written. An existing
     * chat is reopened if closed and claimed by the caller if unassigned, then written to like any reply; starting a
     * chat is an explicit claim, whatever {@code claimOnReply} says.
     *
     * @throws ValidationException if the phone is not 8 to 15 digits, or the message is invalid
     * @throws NotFoundException if the number is not in the workspace, or the caller may not open it
     * @throws NotOnWhatsAppException if no chat exists and WhatsApp does not know the phone
     * @throws LookupFailedException if the device could not tell whether the phone is on WhatsApp; nothing is stored
     * @throws ChatTakenException if the chat is assigned to a user or a team and not visible to the caller; a chat
     *     with neither is free to claim
     */
    public CompletableFuture<MessageView> start(AuthenticatedUser me, String workspaceId, String phoneNumberId,
            NewChatRequest req) {
        String phone = Phones.normalise(req == null ? null : req.phone());
        if (!Phones.isValid(phone)) throw new ValidationException("phone", Phones.MESSAGE);
        PhoneNumber number = numbers.findByIdAndWorkspaceId(phoneNumberId, workspaceId)
                .orElseThrow(() -> new NotFoundException("phone number not found"));
        // writing from a number is seeing it: the owning team's members and admins only (design §3.1)
        if (scopes.visibleNumbers(me, workspaceId).stream().noneMatch(v -> v.number().getId().equals(phoneNumberId))) {
            throw new NotFoundException("phone number not found");
        }
        devices.requireReady(workspaceId, phoneNumberId);
        SendRequest message = new SendRequest(req.text(), req.templateId(), req.values());

        WaWebChat chat = existingChat(workspaceId, phoneNumberId, phone, phone + "@c.us");
        if (chat != null) return sendToExisting(me, workspaceId, chat, message);

        // a number nobody has chatted with: ask WhatsApp (or the cache) whether it exists and under which id; the
        // request thread waits for the device, as the send it replaces would have
        NumberLookup lookup = await(lookups.lookup(workspaceId, phoneNumberId, phone, false));
        if (!lookup.registered()) throw new NotOnWhatsAppException();
        String remoteId = lookup.remoteId();
        chat = chats.findByPhoneNumberIdAndRemoteId(phoneNumberId, remoteId).orElse(null);
        if (chat != null) return sendToExisting(me, workspaceId, chat, message);

        // the contact is only created once the device answered, under the id WhatsApp really used (often a @lid);
        // a contact already known by this phone lends its name to a template meanwhile
        WaWebChat fresh = new WaWebChat();
        fresh.setCustomerId(number.getCustomerId());
        fresh.setWorkspaceId(workspaceId);
        fresh.setPhoneNumberId(phoneNumberId);
        fresh.setRemoteId(remoteId);
        fresh.setContactId(contactRepository.findByWorkspaceIdAndPhone(workspaceId, phone).map(Contact::getId)
                .orElse(null));
        fresh.setStatus(ChatStatus.OPEN);
        fresh.setAssignedUserId(me.userId());
        fresh.setAssignedAt(clock.instant());
        return sender.send(me, workspaceId, fresh, message, phone);
    }

    /**
     * Sends a campaign's message to {@code phone}, which the campaign's runner already checked and looked up as
     * {@code remoteId}: no scope check and no lookup here. An existing chat (the contact's, else that of
     * {@code phone@c.us}, else that of {@code remoteId}) is reopened if closed and keeps its assignment; one with
     * neither a user nor a team is marked with the campaign. Otherwise a new chat is started unassigned and marked
     * with the campaign. A marked chat is routed when the contact first replies to the campaign.
     *
     * @throws NotFoundException if the number is not in the workspace
     * @throws ValidationException if the message is invalid
     */
    public CompletableFuture<MessageView> startForCampaign(CampaignActor actor, String workspaceId,
            String phoneNumberId, String phone, String remoteId, SendRequest message) {
        WaWebChat chat = existingChat(workspaceId, phoneNumberId, phone, phone + "@c.us");
        if (chat == null) chat = chats.findByPhoneNumberIdAndRemoteId(phoneNumberId, remoteId).orElse(null);
        if (chat != null) {
            if (chat.getStatus() == ChatStatus.CLOSED) chat = assignment.reopen(actor, chat);
            // nobody would see the reply to a chat nobody has; stored with the message once the device accepted it
            if (chat.getAssignedUserId() == null && chat.getAssignedTeamId() == null) {
                chat.setCampaignId(actor.campaignId());
            }
            return sender.send(actor, workspaceId, chat, message, null);
        }
        PhoneNumber number = numbers.findByIdAndWorkspaceId(phoneNumberId, workspaceId)
                .orElseThrow(() -> new NotFoundException("phone number not found"));
        WaWebChat fresh = new WaWebChat();
        fresh.setCustomerId(number.getCustomerId());
        fresh.setWorkspaceId(workspaceId);
        fresh.setPhoneNumberId(phoneNumberId);
        fresh.setRemoteId(remoteId);
        fresh.setContactId(contactRepository.findByWorkspaceIdAndPhone(workspaceId, phone).map(Contact::getId)
                .orElse(null));
        fresh.setStatus(ChatStatus.OPEN);
        fresh.setCampaignId(actor.campaignId());
        return sender.send(actor, workspaceId, fresh, message, phone);
    }

    /** Writes to a chat that exists: refused if taken, reopened if closed, claimed if nobody has it. */
    private CompletableFuture<MessageView> sendToExisting(AuthenticatedUser me, String workspaceId, WaWebChat chat,
            SendRequest message) {
        boolean assigned = chat.getAssignedUserId() != null || chat.getAssignedTeamId() != null;
        if (assigned && !scopes.scopeOf(me, workspaceId).covers(chat)) throw new ChatTakenException();
        if (chat.getStatus() == ChatStatus.CLOSED) chat = assignment.reopen(me, chat);
        if (chat.getAssignedUserId() == null) chat = assignment.assign(me, chat, me.userId(), null);
        return sender.send(me, workspaceId, chat, message);
    }

    /** Joins the future; a failure surfaces as the exception it carries, so the API handler maps it. */
    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new IllegalStateException(e.getCause());
        }
    }

    /**
     * The number's chat with this phone: the contact's chat under whatever remote id it lives (a contact first seen
     * as {@code @lid} must not get a second {@code @c.us} chat), else the chat of the {@code @c.us} id.
     */
    private WaWebChat existingChat(String workspaceId, String phoneNumberId, String phone, String remoteId) {
        WaWebChat ofContact = contactRepository.findByWorkspaceIdAndPhone(workspaceId, phone)
                .flatMap(c -> chats.findFirstByPhoneNumberIdAndContactId(phoneNumberId, c.getId())).orElse(null);
        return ofContact != null ? ofContact : chats.findByPhoneNumberIdAndRemoteId(phoneNumberId, remoteId).orElse(null);
    }
}
