package me.nageshk.omnichannel.crm.note.dto;

import me.nageshk.omnichannel.crm.common.CrmTarget;

/** A new note (target required) or an edit of one ({@code version} required, target ignored). */
public record NoteRequest(CrmTarget target, String body, Boolean pinned, Long version) {}
