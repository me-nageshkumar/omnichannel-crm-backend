package me.nageshk.omnichannel.service.automation;

/** Who a test waits for: an existing contact, or a number that has never written; exactly one of the two. */
public record TestStartRequest(String contactId, String phone, boolean treatAsNew) {
}
