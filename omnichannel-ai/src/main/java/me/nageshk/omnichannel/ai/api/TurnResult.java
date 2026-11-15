package me.nageshk.omnichannel.ai.api;

import java.util.Map;

public record TurnResult(String callId, TurnStatus status, String text, Map<String, Object> json,
        AiFinish finish, AiUsage usage, boolean toolsUsed, String conversationId, String runId,
        String errorCode, String error) {}
