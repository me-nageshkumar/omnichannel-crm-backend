package me.nageshk.omnichannel.ai.api;

import java.util.Map;

public record AiFinish(String outcome, Map<String, Object> fields, String reply) {}
