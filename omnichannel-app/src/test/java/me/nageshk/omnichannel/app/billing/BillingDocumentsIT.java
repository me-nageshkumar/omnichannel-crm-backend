package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.billing.BillingPeriod;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import me.nageshk.omnichannel.db.billing.Plan;
import me.nageshk.omnichannel.db.billing.PlanItem;
import me.nageshk.omnichannel.db.billing.PlanRepository;
import me.nageshk.omnichannel.db.billing.PlanStatus;
import me.nageshk.omnichannel.db.billing.Subscription;
import me.nageshk.omnichannel.db.billing.SubscriptionRepository;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest
class BillingDocumentsIT {

    @Autowired PlanRepository plans;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clean() {
        plans.deleteAll();
        subscriptions.deleteAll();
    }

    @Test
    void aPlanKeepsItsMoneyAsDecimals() {
        Plan p = new Plan();
        p.setCode("gold-monthly");
        p.setName("Gold");
        p.setPeriod(BillingPeriod.MONTHLY);
        p.setRank(2);
        p.setItems(List.of(new PlanItem("crm", new BigDecimal("1999.50"))));
        p.setDiscountPercent(new BigDecimal("12.5"));
        p.setLimits(Map.of(LimitKey.USERS, 5));
        p.setRates(Map.of(UsageMeter.WHATSAPP_MESSAGE, new BigDecimal("0.0500")));
        p.setStatus(PlanStatus.DRAFT);
        plans.save(p);

        Plan read = plans.findById("gold-monthly").orElseThrow();
        assertThat(read.getItems().get(0).price()).isEqualByComparingTo("1999.50");
        assertThat(read.getDiscountPercent()).isEqualByComparingTo("12.5");
        assertThat(read.getLimits()).containsEntry(LimitKey.USERS, 5);
        assertThat(read.getRates().get(UsageMeter.WHATSAPP_MESSAGE)).isEqualByComparingTo("0.05");

        Document raw = mongo.getCollection("plans").find(new Document("_id", "gold-monthly")).first();
        Object price = raw.getList("items", Document.class).get(0).get("price");
        assertThat(price).isInstanceOf(Decimal128.class);
        assertThat(raw.get("discountPercent")).isInstanceOf(Decimal128.class);
    }

    @Test
    void aCustomerHasOneSubscription() {
        Subscription a = new Subscription();
        a.setCustomerId("c1");
        subscriptions.save(a);
        Subscription b = new Subscription();
        b.setCustomerId("c1");
        assertThatThrownBy(() -> subscriptions.save(b)).isInstanceOf(DuplicateKeyException.class);
    }
}
