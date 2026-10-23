package me.nageshk.omnichannel.crm.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class ActivityLogTimeTest {

    private final Instant fixed = Instant.parse("2026-03-04T05:06:07Z");
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final ActivityLog log = new ActivityLog(mongo, Clock.fixed(fixed, ZoneOffset.UTC));
    private final CrmContext ctx = new CrmContext("w", "c", "u", Set.of(), null, List.of(), Set.of(), null);
    private final CrmTarget pipeline = new CrmTarget(TargetType.PIPELINE, "p");

    @Test
    void happenedAtDefaultsToTheClock() {
        when(mongo.insert(any(CrmActivity.class))).thenAnswer(i -> i.getArgument(0));
        CrmActivity a = log.record(ctx, ActivityType.SYSTEM, pipeline, null, null, null, "s", null);
        assertThat(a.getHappenedAt()).isEqualTo(fixed);
    }

    @Test
    void unparseableHappenedAtIsAValidationError() {
        assertThatThrownBy(() -> log.record(ctx, ActivityType.SYSTEM, pipeline, null, null, null, "s",
                new HashMap<>(Map.of("happenedAt", "yesterday"))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("happenedAt");
    }
}
