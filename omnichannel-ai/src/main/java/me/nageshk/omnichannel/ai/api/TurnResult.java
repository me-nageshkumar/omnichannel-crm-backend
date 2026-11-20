package me.nageshk.omnichannel.ai.api;

import java.util.Map;

/** progress: what the turn's note_progress calls noted, merged in order (later wins); empty when none. */
public record TurnResult(String callId, TurnStatus status, String text, Map<String, Object> json,
        AiFinish finish, Map<String, Object> progress, AiUsage usage, boolean toolsUsed, String conversationId,
        String runId, String errorCode, String error) {}
