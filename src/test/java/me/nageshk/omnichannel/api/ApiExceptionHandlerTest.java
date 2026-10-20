package me.nageshk.omnichannel.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;

class ApiExceptionHandlerTest {

    @Test
    void concurrentModificationIsConflict() {
        var response = new ApiExceptionHandler().conflict(new OptimisticLockingFailureException("stale"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "CONFLICT").containsKey("message");
    }

    @Test
    void disabledAtHigherLevelIs403WithLevel() {
        var response = new ApiExceptionHandler().disabledAtHigherLevel(
                new DisabledAtHigherLevelException(me.nageshk.omnichannel.enablement.EnableLevel.PLATFORM));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("code", "DISABLED_AT_HIGHER_LEVEL")
                .containsEntry("level", "PLATFORM")
                .containsEntry("message", "Disabled by platform staff. Only platform staff can enable it.");
    }
}
