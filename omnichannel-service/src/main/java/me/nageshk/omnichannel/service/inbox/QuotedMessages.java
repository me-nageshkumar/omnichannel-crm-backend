package me.nageshk.omnichannel.service.inbox;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.channel.whatsapp.message.TemplateSnapshot;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Context;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.meta.api.CloudAccountInfo;
import me.nageshk.omnichannel.meta.api.CloudAccounts;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * What a reply quotes, as stored on it when it arrives (spec 2026-11-21 reply quotes). The quoted message is looked up
 * in the reply's own chat only: by Meta's id (Cloud), else by WhatsApp Web's id ending in {@code _<stanza id>}. When
 * we do not have it, WhatsApp Web's own quoted text is kept (a Cloud quote keeps only the id), and whether it was ours
 * is told from the quoted sender against the number's own phone. A reply stored before its original (a history import
 * walks backwards) is linked when the original arrives.
 */
@Component
public class QuotedMessages {

    static final int MAX_TEXT = 300;

    private final MongoTemplate mongo;
    private final PhoneNumberRepository numbers;
    private final ObjectProvider<CloudAccounts> cloudAccounts;

    QuotedMessages(MongoTemplate mongo, PhoneNumberRepository numbers, ObjectProvider<CloudAccounts> cloudAccounts) {
        this.mongo = mongo;
        this.numbers = numbers;
        this.cloudAccounts = cloudAccounts;
    }

    /** The quote of a message in {@code chatId} of number {@code phoneNumberId}; null when it quotes nothing. */
    public WaWebMessage.Quoted resolve(String chatId, String phoneNumberId, Context context) {
        if (context == null || context.id() == null || context.id().isBlank()) return null;
        String id = context.id();
        WaWebMessage found = chatId == null ? null : find(chatId, id);
        if (found != null) return quoteOf(found, context.from());
        Context.QuotedPreview preview = context.waWeb() == null ? null : context.waWeb().quoted();
        return new WaWebMessage.Quoted(null, id, ours(phoneNumberId, context.from()), context.from(), null,
                preview == null ? null : cut(preview.body()), preview == null ? null : preview.type());
    }

    /**
     * Links the replies of {@code original}'s chat that quote it but could not find it when they arrived; returns how
     * many were linked.
     */
    public long linkLater(WaWebMessage original) {
        if (original == null || original.getId() == null || original.getChatId() == null
                || original.getWaMessageId() == null) {
            return 0;
        }
        List<String> ids = new ArrayList<>(List.of(original.getWaMessageId()));
        int cut = original.getWaMessageId().lastIndexOf('_');
        if (cut >= 0 && cut < original.getWaMessageId().length() - 1) {
            ids.add(original.getWaMessageId().substring(cut + 1));
        }
        WaWebMessage.Quoted q = quoteOf(original, null);
        Query waiting = new Query(Criteria.where("chatId").is(original.getChatId()).and("quoted").ne(null)
                .and("quoted.messageId").is(null).and("quoted.waMessageId").in(ids));
        Update link = new Update().set("quoted.messageId", q.messageId()).set("quoted.fromMe", q.fromMe())
                .set("quoted.type", q.type());
        if (q.text() != null) link.set("quoted.text", q.text());
        if (q.title() != null) link.set("quoted.title", q.title());
        return mongo.updateMulti(waiting, link, WaWebMessage.class).getModifiedCount();
    }

    /** True when WhatsApp marks the message forwarded; null otherwise, so nothing is stored. */
    public static Boolean forwarded(Context context) {
        if (context == null) return null;
        return Boolean.TRUE.equals(context.forwarded()) || Boolean.TRUE.equals(context.frequentlyForwarded())
                ? Boolean.TRUE : null;
    }

    private static WaWebMessage.Quoted quoteOf(WaWebMessage found, String from) {
        TemplateSnapshot t = found.getTemplate();
        String title = t != null && t.header() != null && "TEXT".equalsIgnoreCase(t.header().format())
                ? t.header().text() : null;
        String body = MessageBodies.readable(found.getType(), found.getBody());
        String text = body != null ? body : t == null ? null : t.body();
        return new WaWebMessage.Quoted(found.getId(), found.getWaMessageId(),
                found.getDirection() == MessageDirection.OUT, from, title, cut(text), found.getType());
    }

    /** Whether the quoted sender is the number itself; null when either side is unknown. */
    private Boolean ours(String phoneNumberId, String from) {
        String own = ownDigits(phoneNumberId);
        String quoted = digits(from);
        if (own == null || quoted == null) return null;
        return own.equals(quoted);
    }

    private String ownDigits(String phoneNumberId) {
        if (phoneNumberId == null) return null;
        String own = numbers.findById(phoneNumberId).map(PhoneNumber::getNumber).map(QuotedMessages::digits)
                .orElse(null);
        if (own != null) return own;
        CloudAccounts accounts = cloudAccounts.getIfAvailable();
        return accounts == null ? null : accounts.find(phoneNumberId).map(CloudAccountInfo::displayPhoneNumber)
                .map(QuotedMessages::digits).orElse(null);
    }

    private static String digits(String s) {
        if (s == null) return null;
        String d = s.replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
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
