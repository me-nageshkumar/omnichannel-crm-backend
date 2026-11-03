package me.nageshk.omnichannel.service.automation;

import java.util.List;

/** A contact a test can wait for, with the numbers it has chats on; {@code phone} is null when WhatsApp hides it. */
public record TestContactView(String contactId, String name, String phone, List<TestView.NumberRef> numbers) {
}
