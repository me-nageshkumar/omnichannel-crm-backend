package me.nageshk.omnichannel.service.automation;

import java.util.Map;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.db.automation.AutomationTest;
import org.springframework.http.HttpStatus;

/** The automation API's coded failures. */
public final class AutomationErrors {

    private AutomationErrors() {
    }

    public static CodedException published() {
        return new CodedException(HttpStatus.CONFLICT, "AUTOMATION_RULE_PUBLISHED", null,
                "Move the rule to draft to change it.");
    }

    public static CodedException testRunning(AutomationTest t) {
        return new CodedException(HttpStatus.CONFLICT, "AUTOMATION_TEST_RUNNING", null,
                "This rule is already being tested by " + t.getStartedByName() + ".",
                Map.of("testId", String.valueOf(t.getId()), "startedById", String.valueOf(t.getStartedByUserId()),
                        "startedByName", String.valueOf(t.getStartedByName())));
    }

    public static CodedException testState(String message) {
        return new CodedException(HttpStatus.CONFLICT, "AUTOMATION_TEST_STATE", null, message);
    }
}
