package me.nageshk.omnichannel.service.chatbot.run;

import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.chatbot.InboxMark;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** Where a new run stands in the chat's inbox ({@link InboxMark}); served by the {@code chat_created} index. */
@Component
public class InboxMarks {

    private final MongoTemplate mongo;

    public InboxMarks(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * The message that started the run when the event names one of this chat; else the contact's newest message in
     * the chat (a manual or test start, or an event that is not a message): everything up to it came before the run.
     * Null without a chat or with no message from the contact yet.
     */
    public InboxMark atStart(WaWebChat chat, EventData event) {
        if (chat == null) return null;
        String triggerId = event == null || event.message() == null ? null : event.message().id();
        if (triggerId != null) {
            WaWebMessage trigger = mongo.findOne(new Query(Criteria.where("_id").is(triggerId).and("chatId").is(chat.getId())),
                    WaWebMessage.class);
            InboxMark mark = InboxMark.of(trigger);
            if (mark != null) return mark;
        }
        Query newest = new Query(Criteria.where("chatId").is(chat.getId()).and("direction").is(MessageDirection.IN.name())
                .and("source").is(MessageSource.CONTACT.name()))
                .with(Sort.by(Sort.Order.desc("creationTime"), Sort.Order.desc("_id"))).limit(1);
        return InboxMark.of(mongo.findOne(newest, WaWebMessage.class));
    }
}
