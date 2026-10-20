package me.nageshk.omnichannel.template;

/** Body of duplicate: the brand to copy the template into (may be its own) and the copy's name. */
public record DuplicateRequest(String targetWorkspaceId, String name) {}
