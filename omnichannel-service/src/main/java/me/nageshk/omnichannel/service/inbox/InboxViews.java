package me.nageshk.omnichannel.service.inbox;

import me.nageshk.omnichannel.db.page.MessagePageRepository;
import me.nageshk.omnichannel.service.page.MessagePageService;
import me.nageshk.omnichannel.service.page.MessagePageView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import org.springframework.stereotype.Component;

/** Builds the console's view records from inbox entities, resolving contact, user and team names. */
@Component
@RequiredArgsConstructor
public class InboxViews {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InboxViews.class);

    private final ContactRepository contacts;
    private final UserRepository users;
    private final TeamRepository teams;
    private final WaWebChatRepository waChats;
    private final MessagePageRepository messagePages;
    private final MessagePageService messagePageService;
    private final me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository runs;

    public ChatView chat(WaWebChat chat) {
        return chats(List.of(chat)).get(0);
    }

    /** Resolves contacts, users and teams of all the chats with one {@code findAllById} each. */
    public List<ChatView> chats(List<WaWebChat> chats) {
        Map<String, Contact> contactsById = new HashMap<>();
        contacts.findAllById(ids(chats, WaWebChat::getContactId)).forEach(c -> contactsById.put(c.getId(), c));
        Map<String, String> userNames = new HashMap<>();
        java.util.Set<String> userIds = new java.util.HashSet<>(ids(chats, WaWebChat::getAssignedUserId));
        userIds.addAll(ids(chats, WaWebChat::getLastAgentUserId));
        users.findAllById(userIds).forEach(u -> userNames.put(u.getId(), u.getName()));
        Map<String, String> teamNames = new HashMap<>();
        teams.findAllById(ids(chats, WaWebChat::getAssignedTeamId)).forEach(t -> teamNames.put(t.getId(), t.getName()));
        Map<String, List<WaWebChat>> siblings = siblings(chats);
        return chats.stream().map(c -> toView(c, contactsById.get(c.getContactId()), otherChats(c, siblings),
                ref(c.getAssignedUserId(), userNames.get(c.getAssignedUserId())),
                ref(c.getAssignedTeamId(), teamNames.get(c.getAssignedTeamId())),
                ref(c.getLastAgentUserId(), userNames.get(c.getLastAgentUserId())))).toList();
    }

    public MessageView message(WaWebMessage m) {
        WaWebMessage.Media media = m.getMedia();
        WaWebMessage.Location loc = m.getLocation();
        return new MessageView(m.getId(), m.getChatId(), m.getWaMessageId(), m.getDirection(), m.getSource(),
                m.getType(), MessageBodies.readable(m.getType(), m.getBody()),
                media == null ? null
                        : new MessageView.MediaView(media.mimetype(), media.filename(), media.size(),
                                media.mediaId() != null),
                loc == null ? null : new MessageView.Location(loc.latitude(), loc.longitude(), loc.description()),
                m.getTimestamp(), m.getStatus(), m.getStatusTimes(),
                sentBy(m), m.getTemplateId(), m.getPollOptions(), m.getPollVotes(), m.getCall(),
                m.getBusinessTag(), AdClickView.of(m.getAdClick()), m.getMessagePageId(), messagePage(m));
    }

    /** The page's view, or null when there is none or it cannot be read: a bad page never hides the message. */
    private MessagePageView messagePage(WaWebMessage m) {
        if (m.getMessagePageId() == null) return null;
        try {
            return messagePages.findById(m.getMessagePageId()).map(messagePageService::view).orElse(null);
        } catch (RuntimeException e) {
            log.warn("could not read message page {} of message {}: {}", m.getMessagePageId(), m.getId(), e.toString());
            return null;
        }
    }

    public ActionView action(WaWebChatAction a) {
        return new ActionView(a.getId(), a.getType(), named(a.getByUserId(), a.getByName()),
                ref(a.getToUserId(), a.getToUserName()), ref(a.getToTeamId(), a.getToTeamName()), a.getAt(),
                a.getReason(), a.getReasonDetail(), ref(a.getFromTeamId(), a.getFromTeamName()));
    }

    private ChatView toView(WaWebChat c, Contact contact, List<ChatView.OtherChat> otherChats,
            ChatView.Ref user, ChatView.Ref team,
            ChatView.Ref lastAgent) {
        String phone = contact == null ? null : contact.getPhone();
        WaWebChat.LastMessage last = c.getLastMessage();
        return new ChatView(c.getId(), c.getPhoneNumberId(), c.getRemoteId(),
                new ChatView.Contact(c.getContactId(), contact == null ? null : contact.getName(), phone,
                        phone == null),
                c.getStatus(), user, team, c.getUnreadCount(), c.getLastMessageAt(),
                last == null ? null
                        : new ChatView.LastMessage(last.direction(), last.type(), last.text(), last.status()),
                c.getCreationTime(), lastAgent, AdClickView.of(c.getAdClick()), otherChats, chatbot(c));
    }

    private ChatView.ActiveChatbotView chatbot(WaWebChat c) {
        WaWebChat.ActiveChatbot p = c.getChatbot();
        if (p == null) return null;
        String waitingFor = runs.findById(p.runId()).map(r -> r.getWaiting() == null ? null : switch (r.getWaiting().kind()) {
            case WHATSAPP_REPLY -> "WhatsApp reply";
            case FORM -> "form";
            case TIMER -> "timer";
        }).orElse(null);
        return new ChatView.ActiveChatbotView(p.runId(), p.chatbotId(), p.chatbotName(), p.nodeName(), waitingFor);
    }

    /** One query per distinct number: the chats of the listed contacts, keyed by number and contact. */
    private Map<String, List<WaWebChat>> siblings(List<WaWebChat> chats) {
        Map<String, java.util.Set<String>> contactsByNumber = new HashMap<>();
        for (WaWebChat c : chats) {
            if (c.getContactId() != null && c.getPhoneNumberId() != null) {
                contactsByNumber.computeIfAbsent(c.getPhoneNumberId(), k -> new java.util.HashSet<>())
                        .add(c.getContactId());
            }
        }
        Map<String, List<WaWebChat>> out = new HashMap<>();
        contactsByNumber.forEach((number, contactIds) -> waChats
                .findAllByPhoneNumberIdAndContactIdIn(number, contactIds)
                .forEach(c -> out.computeIfAbsent(number + "|" + c.getContactId(), k -> new java.util.ArrayList<>())
                        .add(c)));
        return out;
    }

    private static List<ChatView.OtherChat> otherChats(WaWebChat c, Map<String, List<WaWebChat>> siblings) {
        if (c.getContactId() == null) return List.of();
        return siblings.getOrDefault(c.getPhoneNumberId() + "|" + c.getContactId(), List.of()).stream()
                .filter(o -> !o.getId().equals(c.getId()))
                .map(o -> new ChatView.OtherChat(o.getId(), o.getRemoteId())).toList();
    }

    private static ChatView.Ref ref(String id, String name) {
        return id == null ? null : new ChatView.Ref(id, name);
    }

    /** A ref when either part is known: a campaign's reopen has a name and no user. */
    private static ChatView.Ref named(String id, String name) {
        return id == null && name == null ? null : new ChatView.Ref(id, name);
    }

    /** A campaign's message is sent by the campaign; any other by its user, if it has one. */
    private static ChatView.Ref sentBy(WaWebMessage m) {
        if (m.getCampaignId() != null && m.getSentByUserId() == null) {
            return new ChatView.Ref(m.getCampaignId(), m.getSentByName());
        }
        return named(m.getSentByUserId(), m.getSentByName());
    }

    private static List<String> ids(List<WaWebChat> chats, Function<WaWebChat, String> idOf) {
        return chats.stream().map(idOf).filter(Objects::nonNull).distinct().toList();
    }
}
