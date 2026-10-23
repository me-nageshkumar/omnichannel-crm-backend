package me.nageshk.omnichannel.service.contact;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoExecutionTimeoutException;
import me.nageshk.omnichannel.common.api.QueryTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.UncategorizedMongoDbException;

class QueryTimeoutsTest {

    @Test
    void timeoutInTheCauseChainBecomesQueryTimeout() {
        assertThatThrownBy(() -> QueryTimeouts.guarded(() -> {
            throw new UncategorizedMongoDbException("x", new MongoExecutionTimeoutException(50, "time limit"));
        })).isInstanceOf(QueryTimeoutException.class)
                .hasMessage(QueryTimeoutException.MESSAGE);
    }

    @Test
    void otherFailuresPassUnchanged() {
        IllegalStateException failure = new IllegalStateException("boom");
        assertThatThrownBy(() -> QueryTimeouts.guarded(() -> {
            throw failure;
        })).isSameAs(failure);
    }
}
