package me.nageshk.omnichannel.ai.provider;

/** Where a file stands in a knowledge store; {@code error} is set only for FAILED. */
public record KnowledgeFileState(Kind kind, String error) {

    public enum Kind { INDEXING, READY, FAILED }
}
