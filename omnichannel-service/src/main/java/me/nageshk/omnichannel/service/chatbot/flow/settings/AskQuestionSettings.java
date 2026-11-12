package me.nageshk.omnichannel.service.chatbot.flow.settings;

public record AskQuestionSettings(String text, AnswerType answerType, String saveAs, String attributeId,
        Integer retries, String retryMessage, String giveUpMessage) {
}
