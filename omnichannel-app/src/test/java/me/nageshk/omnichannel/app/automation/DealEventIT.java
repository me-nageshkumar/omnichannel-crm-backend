package me.nageshk.omnichannel.app.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import me.nageshk.omnichannel.crm.platform.DealLaneChange;
import me.nageshk.omnichannel.crm.platform.DealObserver;
import me.nageshk.omnichannel.db.automation.AutomationEvent;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A deal lane change becomes one queued event, for brands that want it. */
class DealEventIT extends AutomationTestBase {

    private static final String ASHA = "919811100001@c.us";

    @Autowired DealObserver observer;

    private DealLaneChange change(String contactId) {
        return new DealLaneChange(brand.getId(), acme.getId(), "deal-1", "Big order", "WON", new BigDecimal("100"),
                "INR", manager.getId(), null, "pipe-1", "Sales",
                new DealLaneChange.Lane("s-1", "Qualified", "OPEN"), new DealLaneChange.Lane("s-2", "Won", "WON"),
                contactId, n1.getId(), Instant.parse("2026-11-03T05:30:00Z"));
    }

    @Test
    void laneChangeQueuesEvent() {
        rule("Lane", AutomationEventKind.DEAL_LANE_CHANGED, RuleStatus.PUBLISHED, 1, null);
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hello", "Asha");
        queuedClear();

        observer.laneChanged(change(chat.getContactId()));

        assertThat(queued()).hasSize(1);
        AutomationEvent e = queued().get(0);
        assertThat(e.getKind()).isEqualTo(AutomationEventKind.DEAL_LANE_CHANGED);
        assertThat(e.getContactId()).isEqualTo(chat.getContactId());
        assertThat(e.getData().toLane().name()).isEqualTo("Won");
        assertThat(e.getData().fromLane().name()).isEqualTo("Qualified");
        assertThat(e.getData().deal().owner().name()).isEqualTo(manager.getName());
        assertThat(e.getData().deal().amount()).isEqualByComparingTo("100");
        assertThat(e.getData().pipeline().name()).isEqualTo("Sales");
        assertThat(e.getData().chat().id()).isEqualTo(chat.getId());
        assertThat(e.getData().contact().inCrm()).isTrue();
    }

    @Test
    void dealWithoutChatHasNullChat() {
        rule("Lane", AutomationEventKind.DEAL_LANE_CHANGED, RuleStatus.PUBLISHED, 1, null);

        observer.laneChanged(change("no-such-contact"));

        assertThat(queued()).hasSize(1);
        assertThat(queued().get(0).getData().chat()).isNull();
    }

    @Test
    void noRuleNoEvent() {
        observer.laneChanged(change("c-1"));
        assertThat(queued()).isEmpty();
    }

    private void queuedClear() {
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), AutomationEvent.class);
    }
}
