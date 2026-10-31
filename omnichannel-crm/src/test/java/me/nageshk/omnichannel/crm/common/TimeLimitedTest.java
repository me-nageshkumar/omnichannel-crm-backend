package me.nageshk.omnichannel.crm.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoExecutionTimeoutException;
import java.util.function.Supplier;
import me.nageshk.omnichannel.common.api.CodedException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.http.HttpStatus;

class TimeLimitedTest {

    private static final Supplier<CodedException> TIMEOUT = () -> new CodedException(HttpStatus.SERVICE_UNAVAILABLE,
            "X_TIMEOUT", null, "too slow");

    @Test
    void passesTheValueThrough() {
        assertThat(TimeLimited.guard(() -> 42, TIMEOUT)).isEqualTo(42);
    }

    @Test
    void translatesWrappedMongoTimeout() {
        RuntimeException wrapped = new UncategorizedMongoDbException("wrapped",
                new MongoExecutionTimeoutException(50, "operation exceeded time limit"));
        assertThatThrownBy(() -> TimeLimited.guard(() -> { throw wrapped; }, TIMEOUT))
                .isInstanceOf(CodedException.class)
                .satisfies(e -> assertThat(((CodedException) e).code()).isEqualTo("X_TIMEOUT"));
    }

    @Test
    void translatesQueryTimeoutException() {
        assertThatThrownBy(() -> TimeLimited.guard(() -> { throw new QueryTimeoutException("slow"); }, TIMEOUT))
                .isInstanceOf(CodedException.class)
                .satisfies(e -> assertThat(((CodedException) e).code()).isEqualTo("X_TIMEOUT"));
    }

    @Test
    void rethrowsOtherFailures() {
        IllegalStateException other = new IllegalStateException("boom");
        assertThatThrownBy(() -> TimeLimited.guard(() -> { throw other; }, TIMEOUT)).isSameAs(other);
    }
}
