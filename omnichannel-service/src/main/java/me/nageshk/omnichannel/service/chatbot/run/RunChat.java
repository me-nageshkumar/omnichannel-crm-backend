package me.nageshk.omnichannel.service.chatbot.run;

import java.util.Comparator;
import java.util.Optional;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.service.chatbot.node.RunContext;
import org.springframework.stereotype.Component;

/**
 * Which chat a node messages (spec §4.5): the run's chat, else the contact's most recent chat in the brand, which the
 * run then remembers; none → the node fails "no WhatsApp chat for this contact".
 */
@Component
public class RunChat {

    private final WaWebChatRepository chats;
    private final ChatPointer pointer;

    public RunChat(WaWebChatRepository chats, ChatPointer pointer) {
        this.chats = chats;
        this.pointer = pointer;
    }

    public WaWebChat resolve(RunContext ctx) {
        if (ctx.chat() != null) return ctx.chat();
        ChatbotRun run = ctx.run();
        Optional<WaWebChat> found = run.getChatId() == null ? Optional.empty() : chats.findById(run.getChatId());
        if (found.isEmpty() && run.getContactId() != null) {
            found = chats.findAllByWorkspaceIdAndContactId(run.getWorkspaceId(), run.getContactId()).stream()
                    .max(Comparator.comparing(c -> c.getLastMessageAt() == null ? c.getCreationTime() : c.getLastMessageAt(),
                            Comparator.nullsFirst(Comparator.naturalOrder())));
        }
        WaWebChat chat = found.orElseThrow(() -> new NodeFailed("no WhatsApp chat for this contact"));
        if (!chat.getId().equals(run.getChatId())) {
            run.setChatId(chat.getId());
            run.setPhoneNumberId(chat.getPhoneNumberId());
            pointer.set(chat.getId(), run);
        }
        ctx.chat(chat);
        return chat;
    }
}
