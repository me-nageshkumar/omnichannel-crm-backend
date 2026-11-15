package me.nageshk.omnichannel.ai.provider;

public record ToolChoice(Mode mode, String name) {

    public enum Mode { AUTO, REQUIRED, NAMED }
}
