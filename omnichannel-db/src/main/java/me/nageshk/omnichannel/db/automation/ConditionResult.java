package me.nageshk.omnichannel.db.automation;

/** How one leaf condition fared against an event. */
public record ConditionResult(String path, String field, String operator, Object value, Object actual,
        boolean passed) {
}
