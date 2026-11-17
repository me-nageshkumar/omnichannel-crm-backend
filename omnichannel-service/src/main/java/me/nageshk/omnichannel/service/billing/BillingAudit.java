package me.nageshk.omnichannel.service.billing;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.billing.BillingChange;
import me.nageshk.omnichannel.db.billing.BillingChangeRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.service.billing.BillingViews.EventView;
import org.springframework.stereotype.Component;

/** The kept history of every change to a feature group, a plan, a subscription or the billing settings. */
@Component
public class BillingAudit {

    private final BillingChangeRepository changes;
    private final UserRepository users;
    private final Clock clock;

    public BillingAudit(BillingChangeRepository changes, UserRepository users, Clock clock) {
        this.changes = changes;
        this.users = users;
        this.clock = clock;
    }

    /** @param by the acting user's id, or {@code "system"} */
    public void record(String targetType, String targetId, String kind, String summary, Map<String, Object> before,
            Map<String, Object> after, String by) {
        BillingChange c = new BillingChange();
        c.setTargetType(targetType);
        c.setTargetId(targetId);
        c.setKind(kind);
        c.setSummary(summary);
        c.setBefore(before);
        c.setAfter(after);
        c.setBy(by);
        c.setAt(clock.instant());
        changes.save(c);
    }

    /**
     * Records a change under an id of the caller's choosing, so that doing the same step again (an activation that
     * is finished after a failure) leaves one row.
     */
    public void recordOnce(String id, String targetType, String targetId, String kind, String summary, String by) {
        BillingChange c = new BillingChange();
        c.setId(id);
        c.setTargetType(targetType);
        c.setTargetId(targetId);
        c.setKind(kind);
        c.setSummary(summary);
        c.setBy(by);
        c.setAt(clock.instant());
        try {
            changes.insert(c);
        } catch (org.springframework.dao.DuplicateKeyException alreadyRecorded) {
            // the first attempt wrote it
        }
    }

    /** Newest first. */
    public List<EventView> history(String targetType, String targetId) {
        Map<String, String> names = new HashMap<>();
        return changes.findByTargetTypeAndTargetIdOrderByAtDescIdDesc(targetType, targetId).stream()
                .map(c -> new EventView(c.getId(), c.getKind(), c.getBy(),
                        names.computeIfAbsent(c.getBy() == null ? "" : c.getBy(), this::nameOf), c.getAt(),
                        c.getSummary()))
                .toList();
    }

    private String nameOf(String userId) {
        if (userId.isEmpty() || "system".equals(userId)) return "System";
        return users.findById(userId).map(User::getName).orElse("Unknown user");
    }
}
