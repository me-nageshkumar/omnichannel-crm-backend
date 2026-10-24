package me.nageshk.omnichannel.crm.activity.dto;

/**
 * Manual logging: {@code target.type} CONTACT, COMPANY or DEAL; {@code type} CALL, MEETING or EMAIL;
 * {@code happenedAt} an optional ISO-8601 instant, at most a minute ahead.
 */
public record LogRequest(Target target, String type, String summary, String happenedAt) {

    public record Target(String type, String id) {}
}
