package me.nageshk.omnichannel.service.inbox.history;

import me.nageshk.omnichannel.common.api.CodedException;
import org.springframework.http.HttpStatus;

/** The history import API's coded failures. */
public final class HistoryImportErrors {

    private HistoryImportErrors() {
    }

    /** {@code verb} is what could not be done: cancelled, paused or resumed. */
    public static CodedException state(String verb) {
        return new CodedException(HttpStatus.CONFLICT, "HISTORY_IMPORT_STATE", null,
                "This import cannot be " + verb + " now.");
    }
}
