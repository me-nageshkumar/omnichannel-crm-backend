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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Rebuilds chat previews saved when they were cut to 120 characters, which also held the start of a media thumbnail.
 * Calls and WhatsApp's notices saved as "Unsupported message" get their label the same way.
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
                    Criteria.where(TEXT).is("Unsupported message").and("lastMessage.type").in(labelled())));
            long n = mongo.find(stale, WaWebChat.class).stream().filter(this::rebuild).count();
            if (n > 0) log.info("rebuilt the preview of {} chat(s)", n);
        } catch (RuntimeException e) {
            log.warn("could not rebuild chat previews", e);
        }
    }

    /** The types that were saved as "Unsupported message" and now have a label of their own. */
    private static List<String> labelled() {
        List<String> types = new ArrayList<>(MessagePayloads.NOTICE_TYPES);
        types.add("call_log");
        return types;
    }

    private boolean rebuild(WaWebChat chat) {
        WaWebChat.LastMessage last = chat.getLastMessage();
        WaWebMessage newest = messages.findFirstByChatIdOrderByTimestampDescIdDesc(chat.getId()).orElse(null);
        if (newest == null || !Objects.equals(newest.getTimestamp(), chat.getLastMessageAt())
                || !Objects.equals(newest.getType(), last.type())) {
            return false;
        }
        String text = InboxIngestService.preview(newest.getType(), newest.getBody(),
                newest.getMedia() == null ? null : newest.getMedia().filename());
        if (text.equals(last.text())) return false;
        return mongo.updateFirst(new Query(Criteria.where("_id").is(chat.getId()).and(TEXT).is(last.text())),
                new Update().set(TEXT, text), WaWebChat.class).getModifiedCount() > 0;
    }
}
