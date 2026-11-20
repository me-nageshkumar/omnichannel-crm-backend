package me.nageshk.omnichannel.ai.api;

import java.util.List;

/**
 * conversationId null = new conversation; supersedesCallId null = none. trackProgress offers the model {@code
 * note_progress} over the collect keys (none when collect is empty).
 */
public record TurnRequest(String workspaceId, String runId, String chatbotId, String nodeName, String profileId,
        AiOverrides overrides, String extraInstructions, List<String> inputs, List<AiOutcome> outcomes,
        List<AiField> collect, boolean forceFinish, boolean trackProgress, AiReplyFormat replyFormat,
        String conversationId, String supersedesCallId) {}
