package me.nageshk.omnichannel.service.campaign;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import me.nageshk.omnichannel.db.phone.ConnectionType;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.meta.api.CloudAccountInfo;
import me.nageshk.omnichannel.meta.api.CloudAccounts;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * The Cloud numbers a campaign may send from, and Meta's daily limit (spec 2026-11-21 §5.3). The limit is counted
 * per WhatsApp account (WABA), as a stand-in for Meta's business portfolio: the distinct chats that got a template
 * from any of its numbers in the last 24 hours. Holds after Meta asked to wait are kept in memory.
 */
@Component
public class CloudCampaignSenders {

    static final Duration WINDOW = Duration.ofHours(24);

    private final PhoneNumberRepository numbers;
    private final CloudAccounts accounts;
    private final MongoTemplate mongo;
    private final Clock clock;
    private final Map<String, Instant> wabaHolds = new ConcurrentHashMap<>();
    private final Map<String, Instant> numberHolds = new ConcurrentHashMap<>();

    CloudCampaignSenders(PhoneNumberRepository numbers, CloudAccounts accounts, MongoTemplate mongo, Clock clock) {
        this.numbers = numbers;
        this.accounts = accounts;
        this.mongo = mongo;
        this.clock = clock;
    }

    /** The workspace's Cloud numbers that are not disabled, by label. */
    public List<CloudSenderView> list(String ws) {
        Instant now = clock.instant();
        List<CloudSenderView> out = new ArrayList<>();
        for (PhoneNumber n : cloudNumbers(ws)) {
            CloudAccountInfo a = accounts.find(n.getId()).orElse(null);
            String waba = a == null ? null : a.wabaId();
            out.add(new CloudSenderView(n.getId(), n.getNumber(), n.getLabel(), waba, a != null && a.canSend(),
                    a == null ? null : a.qualityRating(), a == null ? null : a.messagingLimitTier(),
                    waba == null ? CloudLimits.dailyLimit(null) : dailyLimit(ws, waba),
                    waba == null ? 0 : usedLast24h(ws, waba, now), heldUntil(n.getId(), now)));
        }
        out.sort(Comparator.comparing(v -> v.label() == null ? v.number() : v.label(),
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return out;
    }

    /**
     * Whether recipients preferring this number may keep waiting for it: it is still a Cloud number of the
     * workspace and not disabled (a refused token or a missing registration can be fixed, so they wait).
     */
    public boolean mayBeWaitedFor(String ws, String phoneNumberId) {
        return numbers.findByIdAndWorkspaceId(phoneNumberId, ws).filter(n -> n.getDisabled() == null
                && n.getConnectionType() == ConnectionType.WHATSAPP_CLOUD_API).isPresent();
    }

    public boolean canSend(String phoneNumberId) {
        return accounts.find(phoneNumberId).map(CloudAccountInfo::canSend).orElse(false);
    }

    public Optional<String> wabaOf(String phoneNumberId) {
        return accounts.find(phoneNumberId).map(CloudAccountInfo::wabaId);
    }

    /** The distinct chats that got a template from the WABA's numbers of the workspace in the last 24 hours. */
    public int usedLast24h(String ws, String wabaId, Instant now) {
        List<String> ids = wabaNumbers(ws, wabaId).stream().map(CloudAccountInfo::phoneNumberId).toList();
        if (ids.isEmpty()) return 0;
        Query q = new Query(Criteria.where("phoneNumberId").in(ids).and("direction").is(MessageDirection.OUT)
                .and("cloudTemplateId").ne(null).and("failure").is(null).and("timestamp").gte(now.minus(WINDOW)));
        return mongo.findDistinct(q, "chatId", WaWebMessage.class, String.class).size();
    }

    /** The highest tier among the WABA's numbers of the workspace; null when unlimited. */
    public Integer dailyLimit(String ws, String wabaId) {
        Integer best = CloudLimits.DEFAULT_LIMIT;
        boolean any = false;
        for (CloudAccountInfo a : wabaNumbers(ws, wabaId)) {
            Integer limit = CloudLimits.dailyLimit(a.messagingLimitTier());
            if (limit == null) return null;
            best = any ? Math.max(best, limit) : limit;
            any = true;
        }
        return best;
    }

    public boolean atLimit(String ws, String wabaId, Instant now) {
        Integer limit = dailyLimit(ws, wabaId);
        return limit != null && usedLast24h(ws, wabaId, now) >= limit;
    }

    public void holdWaba(String wabaId, Instant until) {
        wabaHolds.merge(wabaId, until, (a, b) -> a.isAfter(b) ? a : b);
    }

    public void holdNumber(String phoneNumberId, Instant until) {
        numberHolds.merge(phoneNumberId, until, (a, b) -> a.isAfter(b) ? a : b);
    }

    /** The later of the number's own hold and its WABA's, or null when neither is still running at {@code now}. */
    public Instant heldUntil(String phoneNumberId, Instant now) {
        Instant own = numberHolds.get(phoneNumberId);
        Instant waba = wabaOf(phoneNumberId).map(wabaHolds::get).orElse(null);
        Instant later = own == null ? waba : waba == null || own.isAfter(waba) ? own : waba;
        return later != null && later.isAfter(now) ? later : null;
    }

    private List<PhoneNumber> cloudNumbers(String ws) {
        return numbers.findByWorkspaceIdOrderByNumber(ws).stream()
                .filter(n -> n.getDisabled() == null && n.getConnectionType() == ConnectionType.WHATSAPP_CLOUD_API)
                .toList();
    }

    private List<CloudAccountInfo> wabaNumbers(String ws, String wabaId) {
        return cloudNumbers(ws).stream().map(n -> accounts.find(n.getId()).orElse(null))
                .filter(a -> a != null && wabaId.equals(a.wabaId())).toList();
    }
}
