package me.nageshk.omnichannel.ai.api;

import java.util.Map;

public record AiReplyFormat(Type type, Map<String, Object> schema, String messageField) {

    public enum Type { TEXT, JSON }
}
