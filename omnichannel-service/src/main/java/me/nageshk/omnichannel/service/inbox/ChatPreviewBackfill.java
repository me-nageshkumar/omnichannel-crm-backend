package me.nageshk.omnichannel.service.inbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Rebuilds chat previews saved when they were cut to 120 characters, which also held the start of a media thumbnail.
 * Calls, WhatsApp's notices and what WhatsApp Web cannot show, saved as "Unsupported message", get their label the
 * same way, and a contact card the name on it.
 * Only the preview changes, and only when it belongs to the chat's newest message. Idempotent; every profile. A
 * failure is logged and does not stop the application, as a preview is only a copy.
 */
@Component
@Order(7)
public class ChatPreviewBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChatPreviewBackfill.class);
    private static final int CUT_LENGTH = 120;
    private static final String TEXT = "lastMessage.text";

    private final MongoTemplate mongo;
    private final WaWebMessageRepository messages;

    public ChatPreviewBackfill(MongoTemplate mongo, WaWebMessageRepository messages) {
        this.mongo = mongo;
        this.messages = messages;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Query stale = new Query(new Criteria().orOperator(
                    Criteria.where(TEXT).regex("^[\\s\\S]{" + CUT_LENGTH + "}$"),
                    Criteria.where(TEXT).is("Unsupported message").and("lastMessage.type").in(labelled()),
                    Criteria.where(TEXT).is("👤 Contact card").and("lastMessage.type").is("vcard")));
            long n = mongo.find(stale, WaWebChat.class).stream().filter(this::rebuild).count();
            if (n > 0) log.info("rebuilt the preview of {} chat(s)", n);
        } catch (RuntimeException e) {
            log.warn("could not rebuild chat previews", e);
        }
    }

    /** A notice no longer counts as the chat's last message: the real one before it takes the preview, time unchanged. */
    private boolean showRealMessage(WaWebChat chat) {
        WaWebMessage real = mongo.findOne(new Query(Criteria.where("chatId").is(chat.getId()).and("type")
                .nin(MessagePayloads.NOTICE_TYPES)).with(Sort.by(Sort.Direction.DESC, "timestamp", "_id")),
                WaWebMessage.class);
        if (real == null) return false;
        WaWebChat.LastMessage shown = new WaWebChat.LastMessage(real.getDirection(), real.getType(),
                InboxIngestService.preview(real),
                real.getStatus());
        return mongo.updateFirst(new Query(Criteria.where("_id").is(chat.getId()).and("lastMessage.type")
                .is(chat.getLastMessage().type())), new Update().set("lastMessage", shown), WaWebChat.class)
                .getModifiedCount() > 0;
    }

    /** The types that were saved as "Unsupported message" and now have a label of their own. */
    private static List<String> labelled() {
        List<String> types = new ArrayList<>(MessagePayloads.NOTICE_TYPES);
        types.addAll(List.of("call_log", "biz_content_placeholder", "unknown", "newsletter_follower_invite", "album",
                "rich_response"));
        return types;
    }

    private boolean rebuild(WaWebChat chat) {
        WaWebChat.LastMessage last = chat.getLastMessage();
        if (MessagePayloads.isNoticeType(last.type()) && showRealMessage(chat)) return true;
        WaWebMessage newest = messages.findFirstByChatIdOrderByTimestampDescIdDesc(chat.getId()).orElse(null);
        if (newest == null || !Objects.equals(newest.getTimestamp(), chat.getLastMessageAt())
                || !Objects.equals(newest.getType(), last.type())) {
            return false;
        }
        String text = InboxIngestService.preview(newest);
        if (text.equals(last.text())) return false;
        return mongo.updateFirst(new Query(Criteria.where("_id").is(chat.getId()).and(TEXT).is(last.text())),
                new Update().set(TEXT, text), WaWebChat.class).getModifiedCount() > 0;
    }
}
