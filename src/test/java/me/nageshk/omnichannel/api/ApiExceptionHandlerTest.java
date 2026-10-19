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
}
