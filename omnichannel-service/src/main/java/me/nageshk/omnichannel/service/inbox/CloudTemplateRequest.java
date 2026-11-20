package me.nageshk.omnichannel.service.inbox;

import java.util.Map;

/** A Meta template to send: the imported template, its blanks' values, and our cloud media id for a media header. */
public record CloudTemplateRequest(String cloudTemplateId, Map<String, String> values, String headerMediaId) {}
