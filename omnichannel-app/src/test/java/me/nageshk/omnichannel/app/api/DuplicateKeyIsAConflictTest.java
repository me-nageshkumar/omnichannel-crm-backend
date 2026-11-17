package me.nageshk.omnichannel.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

/** Two requests creating the same record at once: the loser is told to reload, not shown a server error. */
class DuplicateKeyIsAConflictTest {

    @Test
    void aDuplicateKeyAnswers409() {
        var response = new ApiExceptionHandler().duplicateKey(new DuplicateKeyException("E11000"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "CONFLICT");
    }
}
