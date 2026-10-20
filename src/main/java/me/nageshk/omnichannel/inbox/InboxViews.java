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
        users.findAllById(ids(chats, WaWebChat::getAssignedUserId)).forEach(u -> userNames.put(u.getId(), u.getName()));
        Map<String, String> teamNames = new HashMap<>();
        teams.findAllById(ids(chats, WaWebChat::getAssignedTeamId)).forEach(t -> teamNames.put(t.getId(), t.getName()));
        return chats.stream().map(c -> toView(c, contactsById.get(c.getContactId()),
                ref(c.getAssignedUserId(), userNames.get(c.getAssignedUserId())),
                ref(c.getAssignedTeamId(), teamNames.get(c.getAssignedTeamId())))).toList();
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
                ref(m.getSentByUserId(), m.getSentByName()), m.getTemplateId());
    }

    public ActionView action(WaWebChatAction a) {
        return new ActionView(a.getId(), a.getType(), ref(a.getByUserId(), a.getByName()),
                ref(a.getToUserId(), a.getToUserName()), ref(a.getToTeamId(), a.getToTeamName()), a.getAt());
    }

    private static ChatView toView(WaWebChat c, Contact contact, ChatView.Ref user, ChatView.Ref team) {
        String phone = contact == null ? null : contact.getPhone();
        WaWebChat.LastMessage last = c.getLastMessage();
        return new ChatView(c.getId(), c.getPhoneNumberId(), c.getRemoteId(),
                new ChatView.Contact(c.getContactId(), contact == null ? null : contact.getName(), phone,
                        phone == null),
                c.getStatus(), user, team, c.getUnreadCount(), c.getLastMessageAt(),
                last == null ? null
                        : new ChatView.LastMessage(last.direction(), last.type(), last.text(), last.status()),
                c.getCreationTime());
    }

    private static ChatView.Ref ref(String id, String name) {
        return id == null ? null : new ChatView.Ref(id, name);
    }

    private static List<String> ids(List<WaWebChat> chats, Function<WaWebChat, String> idOf) {
        return chats.stream().map(idOf).filter(Objects::nonNull).distinct().toList();
    }
}
