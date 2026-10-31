package me.nageshk.omnichannel.crm.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class CrmJobLocksTest {

    private CrmJobLocks locks;

    @BeforeEach
    void theLeaseCannotBeGivenBack() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(CrmJobLock.class)))
                .thenThrow(new DataAccessResourceFailureException("the server went away"));
        locks = new CrmJobLocks(mongo, Clock.systemUTC());
    }

    @Test
    void aJobsOwnFailureIsNotHiddenByTheLeaseGoingBackBadly() {
        IllegalStateException failure = new IllegalStateException("the job broke");
        assertThatThrownBy(() -> locks.runExclusively("job", Duration.ofMinutes(1), () -> {
            throw failure;
        })).isSameAs(failure);
    }

    @Test
    void aFinishedJobStillCountsAsRunWhenTheLeaseCannotGoBack() {
        assertThat(locks.runExclusively("job", Duration.ofMinutes(1), () -> { })).isTrue();
    }
}
