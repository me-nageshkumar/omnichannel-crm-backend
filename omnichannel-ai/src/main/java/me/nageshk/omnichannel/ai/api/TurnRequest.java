package me.nageshk.omnichannel.ai.api;

import java.util.List;

/** conversationId null = new conversation; supersedesCallId null = none. */
public record TurnRequest(String workspaceId, String runId, String chatbotId, String nodeName, String profileId,
        AiOverrides overrides, String extraInstructions, List<String> inputs, List<AiOutcome> outcomes,
        List<AiField> collect, boolean forceFinish, AiReplyFormat replyFormat,
        String conversationId, String supersedesCallId) {}
