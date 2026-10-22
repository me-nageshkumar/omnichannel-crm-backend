package me.nageshk.omnichannel.inbox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.ContactRepository;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import org.springframework.stereotype.Component;

/** Builds the console's view records from inbox entities, resolving contact, user and team names. */
@Component
@RequiredArgsConstructor
public class InboxViews {

    private final ContactRepository contacts;
    private final UserRepository users;
    private final TeamRepository teams;

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
        return chats.stream().map(c -> toView(c, contactsById.get(c.getContactId()),
                ref(c.getAssignedUserId(), userNames.get(c.getAssignedUserId())),
                ref(c.getAssignedTeamId(), teamNames.get(c.getAssignedTeamId())),
                ref(c.getLastAgentUserId(), userNames.get(c.getLastAgentUserId())))).toList();
    }

    public MessageView message(WaWebMessage m) {
        WaWebMessage.Media media = m.getMedia();
        WaWebMessage.Location loc = m.getLocation();
        return new MessageView(m.getId(), m.getChatId(), m.getWaMessageId(), m.getDirection(), m.getSource(),
                m.getType(), m.getBody(),
                media == null ? null
                        : new MessageView.MediaView(media.mimetype(), media.filename(), media.size(),
                                media.mediaId() != null),
                loc == null ? null : new MessageView.Location(loc.latitude(), loc.longitude(), loc.description()),
                m.getTimestamp(), m.getStatus(), m.getStatusTimes(),
                sentBy(m), m.getTemplateId());
    }

    public ActionView action(WaWebChatAction a) {
        return new ActionView(a.getId(), a.getType(), named(a.getByUserId(), a.getByName()),
                ref(a.getToUserId(), a.getToUserName()), ref(a.getToTeamId(), a.getToTeamName()), a.getAt(),
                a.getReason(), ref(a.getFromTeamId(), a.getFromTeamName()));
    }

    private static ChatView toView(WaWebChat c, Contact contact, ChatView.Ref user, ChatView.Ref team,
            ChatView.Ref lastAgent) {
        String phone = contact == null ? null : contact.getPhone();
        WaWebChat.LastMessage last = c.getLastMessage();
        return new ChatView(c.getId(), c.getPhoneNumberId(), c.getRemoteId(),
                new ChatView.Contact(c.getContactId(), contact == null ? null : contact.getName(), phone,
                        phone == null),
                c.getStatus(), user, team, c.getUnreadCount(), c.getLastMessageAt(),
                last == null ? null
                        : new ChatView.LastMessage(last.direction(), last.type(), last.text(), last.status()),
                c.getCreationTime(), lastAgent);
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
