package me.nageshk.omnichannel.service.template;

import java.util.Map;

/** Input to a render: an optional contact in the brand, and the agent's value for each agent placeholder. */
public record RenderRequest(String contactId, Map<String, String> values) {}
