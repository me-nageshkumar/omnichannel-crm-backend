package me.nageshk.omnichannel.ai.provider;

/** The decrypted key, held only for the length of a call; never logged. */
public record AiCredential(String apiKey) {

    @Override
    public String toString() {
        return "AiCredential[apiKey=****]";
    }
}
