package me.nageshk.omnichannel.service.campaign;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignSender;
import me.nageshk.omnichannel.db.campaign.CampaignSenderRepository;
import me.nageshk.omnichannel.db.campaign.CampaignSettings;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.campaign.SenderState;
import me.nageshk.omnichannel.db.phone.ConnectionType;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.service.inbox.InboxDevices;
import me.nageshk.omnichannel.service.phone.PhoneNumberService;
import me.nageshk.omnichannel.service.phone.PhoneNumberStatus;
import me.nageshk.omnichannel.service.phone.PhoneNumberView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** The senders of a workspace: its enabled WhatsApp Web numbers, each with its warm-up and admin state. */
@Service
public class CampaignSenderService {

    private static final int MAX_RUNS_ON = 100;
    private static final Set<RecipientStatus> USED = EnumSet.of(RecipientStatus.SENDING, RecipientStatus.SENT,
            RecipientStatus.DELIVERED, RecipientStatus.READ, RecipientStatus.FAILED);

    private final CampaignSenderRepository repository;
    private final CampaignSettingsService settingsService;
    private final PhoneNumberRepository numbers;
    private final PhoneNumberService phoneNumbers;
    private final InboxDevices devices;
    private final MongoTemplate mongo;
    private final PacingRandom random;
    private final SenderHealth health;
    private final Clock clock;

    CampaignSenderService(CampaignSenderRepository repository, CampaignSettingsService settingsService,
            PhoneNumberRepository numbers, PhoneNumberService phoneNumbers, InboxDevices devices,
            MongoTemplate mongo, PacingRandom random, SenderHealth health, Clock clock) {
        this.repository = repository;
        this.settingsService = settingsService;
        this.numbers = numbers;
        this.phoneNumbers = phoneNumbers;
        this.devices = devices;
        this.mongo = mongo;
        this.random = random;
        this.health = health;
        this.clock = clock;
    }

    /** The number's sender; the first call stores it, warming up from day one. */
    public CampaignSender ensure(String workspaceId, String phoneNumberId) {
        return repository.findByPhoneNumberId(phoneNumberId).orElseGet(() -> {
            CampaignSender s = new CampaignSender();
            s.setPhoneNumberId(phoneNumberId);
            s.setWorkspaceId(workspaceId);
            s.setState(SenderState.WARMING);
            s.setSendingDays(0);
            try {
                return repository.insert(s);
            } catch (DuplicateKeyException raced) {
                return repository.findByPhoneNumberId(phoneNumberId).orElseThrow(() -> raced);
            }
        });
    }

    /** The number's sender as stored now, or null. */
    CampaignSender find(String phoneNumberId) {
        return repository.findByPhoneNumberId(phoneNumberId).orElse(null);
    }

    /**
     * Whether recipients may keep waiting for this number: an enabled WhatsApp Web number of the workspace whose
     * sender is not RETIRED.
     */
    boolean mayBeWaitedFor(String workspaceId, String phoneNumberId) {
        PhoneNumber n = numbers.findByIdAndWorkspaceId(phoneNumberId, workspaceId).orElse(null);
        if (n == null || n.getDisabled() != null || n.getConnectionType() == ConnectionType.WHATSAPP_CLOUD_API) {
            return false;
        }
        return repository.findByPhoneNumberId(phoneNumberId).map(s -> s.getState() != SenderState.RETIRED)
                .orElse(true);
    }

    /** Applies what time alone changes: a finished rest, the end of the warm-up, a new day's start offset. */
    CampaignSender refresh(CampaignSender s, CampaignSettings settings, Instant now) {
        LocalDate today = SenderPolicy.today(settings, now);
        boolean changed = false;
        if (s.getState() == SenderState.RESTING && s.getRestUntil() != null && !s.getRestUntil().isAfter(now)) {
            changed |= transition(s, SenderState.RESTING, new Update().set("state", SenderState.WARMING)
                    .set("warmupStartedAt", null).set("restUntil", null).set("lastSendingDay", null)
                    .set("sendingDays", 0));
        }
        if (s.getState() == SenderState.WARMING
                && SenderPolicy.ageDay(s.getSendingDays(), s.getLastSendingDay(), today) >= SenderPolicy.ACTIVE_FROM_DAY) {
            changed |= transition(s, SenderState.WARMING, new Update().set("state", SenderState.ACTIVE));
        }
        if (!today.equals(s.getDayStartFor())) {
            boolean rolled = mongo.updateFirst(byId(s).addCriteria(Criteria.where("dayStartFor").ne(today)),
                    new Update().set("dayStartOffsetMinutes", random.dayStartOffsetMinutes())
                            .set("dayStartFor", today), CampaignSender.class).getModifiedCount() > 0;
            changed |= rolled;
            Boolean low = rolled ? health.lowReplyRate(s.getPhoneNumberId(), now) : null;
            if (low != null && low != s.isLowReplyRate()) {
                mongo.updateFirst(byId(s), new Update().set("lowReplyRate", low), CampaignSender.class);
            }
        }
        return changed ? repository.findById(s.getId()).orElse(s) : s;
    }

    private boolean transition(CampaignSender s, SenderState from, Update update) {
        return mongo.updateFirst(byId(s).addCriteria(Criteria.where("state").is(from)), update,
                CampaignSender.class).getModifiedCount() > 0;
    }

    private static Query byId(CampaignSender s) {
        return new Query(Criteria.where("_id").is(s.getId()));
    }

    int capToday(CampaignSender s, CampaignSettings settings, Instant now) {
        int age = SenderPolicy.ageDay(s.getSendingDays(), s.getLastSendingDay(), SenderPolicy.today(settings, now));
        return SenderPolicy.cap(age, s.isLowReplyRate(), settings);
    }

    /** Attempts of today that took a slot: a message in flight, sent or failed. */
    public long usedToday(String phoneNumberId, CampaignSettings settings, Instant now) {
        return mongo.count(new Query(Criteria.where("senderId").is(phoneNumberId).and("status").in(USED)
                .and("attemptedAt").gte(SenderPolicy.startOfDay(settings, now))), CampaignRecipient.class);
    }

    public long hourlyUsed(String phoneNumberId, Instant now) {
        return mongo.count(new Query(Criteria.where("senderId").is(phoneNumberId)
                .and("attemptedAt").gt(now.minus(Duration.ofMinutes(60)))), CampaignRecipient.class);
    }

    public List<SenderView> list(String workspaceId) {
        return viewsOf(workspaceId, eligible(workspaceId));
    }

    /** Only the given numbers, when they are senders of the workspace. */
    public List<SenderView> views(String workspaceId, List<String> phoneNumberIds) {
        return viewsOf(workspaceId, eligible(workspaceId).stream().filter(n -> phoneNumberIds.contains(n.getId())).toList());
    }

    public SenderView update(String workspaceId, String phoneNumberId, SenderUpdate req) {
        PhoneNumber number = eligible(workspaceId).stream().filter(n -> n.getId().equals(phoneNumberId)).findFirst()
                .orElseThrow(() -> new NotFoundException("sender not found"));
        CampaignSettings settings = settingsService.get(workspaceId);
        Instant now = clock.instant();
        CampaignSender s = refresh(ensure(workspaceId, phoneNumberId), settings, now);
        if (req.action() != null) {
            act(s, req.action(), settings, now);
        } else {
            String runsOn = req.runsOn() == null ? null : req.runsOn().trim();
            if (runsOn != null && runsOn.length() > MAX_RUNS_ON)
                throw new ValidationException("runsOn", "must be at most 100 characters");
            mongo.updateFirst(byId(s), new Update().set("runsOn", runsOn == null || runsOn.isEmpty() ? null : runsOn),
                    CampaignSender.class);
        }
        return viewsOf(workspaceId, List.of(number)).get(0);
    }

    private void act(CampaignSender s, SenderAction action, CampaignSettings settings, Instant now) {
        boolean done = switch (action) {
            case REST -> transition(s, EnumSet.of(SenderState.WARMING, SenderState.ACTIVE),
                    new Update().set("state", SenderState.RESTING)
                            .set("restUntil", now.plus(Duration.ofDays(settings.getRestDays()))));
            case RETIRE -> transition(s, EnumSet.complementOf(EnumSet.of(SenderState.RETIRED)),
                    new Update().set("state", SenderState.RETIRED));
            case REACTIVATE -> transition(s, EnumSet.of(SenderState.RESTING, SenderState.SUSPENDED, SenderState.RETIRED),
                    new Update().set("state", SenderState.WARMING).set("warmupStartedAt", null)
                            .set("restUntil", null).set("lastSendingDay", null).set("sendingDays", 0)
                            .set("healthFrom", now));
            case RESUME -> mongo.updateFirst(byId(s).addCriteria(Criteria.where("pausedUntil").gt(now)),
                    new Update().set("pausedUntil", null).set("pauseReason", null).set("healthFrom", now),
                    CampaignSender.class).getModifiedCount() > 0;
        };
        if (!done) throw CampaignErrors.senderState(switch (action) {
            case REST -> "rested";
            case RETIRE -> "retired";
            case REACTIVATE -> "reactivated";
            case RESUME -> "resumed";
        });
    }

    private boolean transition(CampaignSender s, Set<SenderState> from, Update update) {
        return mongo.updateFirst(byId(s).addCriteria(Criteria.where("state").in(from)), update,
                CampaignSender.class).getModifiedCount() > 0;
    }

    public void paced(String phoneNumberId, Instant nextSendAt) {
        mongo.updateFirst(new Query(Criteria.where("phoneNumberId").is(phoneNumberId)),
                new Update().set("nextSendAt", nextSendAt), CampaignSender.class);
    }

    /** Counts today as a sending day once, starts the warm-up clock, and notes the time. */
    public void sendAttempted(String phoneNumberId, LocalDate today, Instant now) {
        Criteria sender = Criteria.where("phoneNumberId").is(phoneNumberId);
        mongo.updateFirst(new Query(sender).addCriteria(Criteria.where("lastSendingDay").ne(today)),
                new Update().inc("sendingDays", 1).set("lastSendingDay", today), CampaignSender.class);
        mongo.updateFirst(new Query(Criteria.where("phoneNumberId").is(phoneNumberId)
                .and("warmupStartedAt").is(null)), new Update().set("warmupStartedAt", now), CampaignSender.class);
        mongo.updateFirst(new Query(Criteria.where("phoneNumberId").is(phoneNumberId)),
                new Update().set("lastSendAt", now), CampaignSender.class);
    }

    /** The workspace's enabled WhatsApp Web numbers, by number. */
    private List<PhoneNumber> eligible(String workspaceId) {
        return numbers.findByWorkspaceIdOrderByNumber(workspaceId).stream()
                .filter(n -> n.getDisabled() == null && n.getConnectionType() != ConnectionType.WHATSAPP_CLOUD_API)
                .toList();
    }

    private List<SenderView> viewsOf(String workspaceId, List<PhoneNumber> list) {
        if (list.isEmpty()) return List.of();
        CampaignSettings settings = settingsService.get(workspaceId);
        Instant now = clock.instant();
        Map<String, PhoneNumberStatus.Status> statuses = phoneNumbers.views(list).stream()
                .collect(Collectors.toMap(PhoneNumberView::id, PhoneNumberView::status));
        return list.stream().map(n -> {
            CampaignSender s = refresh(ensure(workspaceId, n.getId()), settings, now);
            boolean paused = s.getPausedUntil() != null && s.getPausedUntil().isAfter(now);
            int age = SenderPolicy.ageDay(s.getSendingDays(), s.getLastSendingDay(),
                    SenderPolicy.today(settings, now));
            return new SenderView(n.getId(), n.getNumber(), n.getLabel(), s.getState(), age,
                    capToday(s, settings, now), usedToday(n.getId(), settings, now), hourlyUsed(n.getId(), now),
                    statuses.get(n.getId()), devices.isReady(workspaceId, n.getId()),
                    paused ? s.getPausedUntil() : null, paused ? s.getPauseReason() : null, s.getHalfPaceUntil(),
                    s.isLowReplyRate(), s.getRestUntil(), s.getRunsOn(), s.getLogoutAt(), s.getLastSendAt(),
                    s.getNextSendAt(), s.getWarmupStartedAt());
        }).toList();
    }
}
