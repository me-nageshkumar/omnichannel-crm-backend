package me.nageshk.omnichannel.service.automation;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import me.nageshk.omnichannel.db.automation.AutomationEvent;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationEventRecordRepository;
import me.nageshk.omnichannel.db.automation.AutomationEventRepository;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.automation.EventStatus;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.user.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** Turns what happened into an event document of the queue. Runs inside the ingestion, so it stays small. */
@Component
public class AutomationEventWriter {

    private final AutomationGate gate;
    private final AutomationEventRepository events;
    private final AutomationEventRecordRepository history;
    private final WaWebMessageRepository messages;
    private final WaWebChatRepository chats;
    private final ContactRepository contacts;
    private final PhoneNumberRepository numbers;
    private final UserRepository users;
    private final TeamRepository teams;
    private final AutomationCrm crm;
    private final Clock clock;

    AutomationEventWriter(AutomationGate gate, AutomationEventRepository events,
            AutomationEventRecordRepository history, WaWebMessageRepository messages, WaWebChatRepository chats, ContactRepository contacts,
            PhoneNumberRepository numbers, UserRepository users, TeamRepository teams, AutomationCrm crm,
            Clock clock) {
        this.gate = gate;
        this.events = events;
        this.history = history;
        this.messages = messages;
        this.chats = chats;
        this.contacts = contacts;
        this.numbers = numbers;
        this.users = users;
        this.teams = teams;
        this.crm = crm;
        this.clock = clock;
    }

    /** Queues a MESSAGE_RECEIVED event for the stored incoming message, unless nobody wants it. */
    public void messageReceived(WaWebChat chat, WaWebMessage message) {
        Contact contact = chat.getContactId() == null ? null : contacts.findById(chat.getContactId()).orElse(null);
        String phone = contact == null ? null : contact.getPhone();
        if (!gate.wants(chat.getWorkspaceId(), AutomationEventKind.MESSAGE_RECEIVED, chat.getContactId(), phone)) return;
        String id = "m-" + message.getId();
        if (history.existsById(id)) return;
        boolean isNew = !messages.existsByChatIdAndDirectionAndTypeInAndIdNot(chat.getId(), MessageDirection.IN,
                MessageKinds.REAL_TYPES, message.getId());
        PhoneNumber number = numbers.findById(chat.getPhoneNumberId()).orElse(null);
        EventData data = new EventData(
                new EventData.ChatData(chat.getId(), isNew, chat.getStatus(), userRef(chat.getAssignedUserId()),
                        teamRef(chat.getAssignedTeamId())),
                new EventData.MessageData(message.getId(), message.getBody(),
                        MessageKinds.kindOf(message.getType()).orElse(null)),
                new EventData.NumberData(chat.getPhoneNumberId(), number == null ? null : number.getNumber(),
                        number == null ? null : number.getLabel()),
                new EventData.ContactData(chat.getContactId(), contact == null ? null : contact.getName(), phone,
                        contact != null && crm.inCrm(chat.getWorkspaceId(), contact.getId(), chat.getPhoneNumberId()),
                        contact != null && contact.getDoNotContact() != null),
                null, null, null, null);
        AutomationEvent e = new AutomationEvent();
        e.setId(id);
        e.setCustomerId(chat.getCustomerId());
        e.setWorkspaceId(chat.getWorkspaceId());
        e.setKind(AutomationEventKind.MESSAGE_RECEIVED);
        e.setData(data);
        e.setContactId(chat.getContactId());
        e.setContactPhone(phone);
        e.setStatus(EventStatus.PENDING);
        e.setCreatedAt(clock.instant());
        try {
            events.insert(e);
        } catch (DuplicateKeyException ignored) {
            // the same message was handled before
        }
    }

    /** The facts of a deal that moved to another lane; the service's own type, the CRM's is mapped to it. */
    public record DealMove(String workspaceId, String customerId, String dealId, String title, String status,
            BigDecimal amount, String currency, String ownerId, String lostReason, String pipelineId,
            String pipelineName, EventData.LaneData from, EventData.LaneData to, String contactId,
            String phoneNumberId) {
    }

    /** Queues a DEAL_LANE_CHANGED event for the move, unless nobody wants it. */
    public void dealLaneChanged(DealMove m) {
        if (!gate.wants(m.workspaceId(), AutomationEventKind.DEAL_LANE_CHANGED, m.contactId(), null)) return;
        Contact contact = m.contactId() == null ? null : contacts.findById(m.contactId()).orElse(null);
        WaWebChat chat = m.contactId() == null || m.phoneNumberId() == null ? null
                : chats.findFirstByPhoneNumberIdAndContactId(m.phoneNumberId(), m.contactId()).orElse(null);
        PhoneNumber number = m.phoneNumberId() == null ? null : numbers.findById(m.phoneNumberId()).orElse(null);
        EventData data = new EventData(
                chat == null ? null
                        : new EventData.ChatData(chat.getId(), false, chat.getStatus(),
                                userRef(chat.getAssignedUserId()), teamRef(chat.getAssignedTeamId())),
                null,
                new EventData.NumberData(m.phoneNumberId(), number == null ? null : number.getNumber(),
                        number == null ? null : number.getLabel()),
                new EventData.ContactData(m.contactId(), contact == null ? null : contact.getName(),
                        contact == null ? null : contact.getPhone(), true,
                        contact != null && contact.getDoNotContact() != null),
                new EventData.DealData(m.dealId(), m.title(), m.status(), m.amount(), m.currency(),
                        userRef(m.ownerId()), m.lostReason()),
                new EventData.Ref(m.pipelineId(), m.pipelineName()), m.from(), m.to());
        AutomationEvent e = new AutomationEvent();
        e.setId("d-" + UUID.randomUUID());
        e.setCustomerId(m.customerId());
        e.setWorkspaceId(m.workspaceId());
        e.setKind(AutomationEventKind.DEAL_LANE_CHANGED);
        e.setData(data);
        e.setContactId(m.contactId());
        e.setContactPhone(contact == null ? null : contact.getPhone());
        e.setStatus(EventStatus.PENDING);
        e.setCreatedAt(clock.instant());
        events.insert(e);
    }

    private EventData.Ref userRef(String userId) {
        return userId == null ? null
                : new EventData.Ref(userId, users.findById(userId).map(u -> u.getName()).orElse(null));
    }

    private EventData.Ref teamRef(String teamId) {
        return teamId == null ? null
                : new EventData.Ref(teamId, teams.findById(teamId).map(t -> t.getName()).orElse(null));
    }
}
