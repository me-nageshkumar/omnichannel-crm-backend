package me.nageshk.omnichannel.service.inbox;

import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.channel.whatsapp.message.TemplateSnapshot;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Context;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * What a reply quotes, as stored on it when it arrives (spec 2026-11-21 reply quotes). The quoted message is looked up
 * in the reply's own chat only: by Meta's id (Cloud), else by WhatsApp Web's id ending in {@code _<stanza id>}. When
 * we do not have it, WhatsApp Web's own quoted text is kept; a Cloud quote keeps only the id.
 */
@Component
public class QuotedMessages {

    static final int MAX_TEXT = 300;

    private final MongoTemplate mongo;

    QuotedMessages(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** The quote of a message in {@code chatId} with this context; null when it quotes nothing. */
    public WaWebMessage.Quoted resolve(String chatId, Context context) {
        if (context == null || context.id() == null || context.id().isBlank()) return null;
        String id = context.id();
        WaWebMessage found = chatId == null ? null : find(chatId, id);
        if (found != null) {
            TemplateSnapshot t = found.getTemplate();
            String title = t != null && t.header() != null && "TEXT".equalsIgnoreCase(t.header().format())
                    ? t.header().text() : null;
            String text = found.getBody() != null ? found.getBody() : t == null ? null : t.body();
            return new WaWebMessage.Quoted(found.getId(), found.getWaMessageId(),
                    found.getDirection() == MessageDirection.OUT, context.from(), title, cut(text), found.getType());
        }
        Context.QuotedPreview preview = context.waWeb() == null ? null : context.waWeb().quoted();
        return new WaWebMessage.Quoted(null, id, false, context.from(), null,
                preview == null ? null : cut(preview.body()), preview == null ? null : preview.type());
    }

    /** True when WhatsApp marks the message forwarded; null otherwise, so nothing is stored. */
    public static Boolean forwarded(Context context) {
        if (context == null) return null;
        return Boolean.TRUE.equals(context.forwarded()) || Boolean.TRUE.equals(context.frequentlyForwarded())
                ? Boolean.TRUE : null;
    }

    private WaWebMessage find(String chatId, String id) {
        WaWebMessage exact = mongo.findOne(new Query(Criteria.where("chatId").is(chatId).and("waMessageId").is(id)),
                WaWebMessage.class);
        if (exact != null) return exact;
        return mongo.findOne(new Query(Criteria.where("chatId").is(chatId).and("waMessageId")
                .regex("_" + Pattern.quote(id) + "$")), WaWebMessage.class);
    }

    private static String cut(String text) {
        if (text == null) return null;
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }
}
