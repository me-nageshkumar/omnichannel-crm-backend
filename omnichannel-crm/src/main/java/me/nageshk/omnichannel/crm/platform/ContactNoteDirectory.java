package me.nageshk.omnichannel.crm.platform;

import java.util.List;

/** The inbox's contact notes, read only. */
public interface ContactNoteDirectory {

    /** Newest first. */
    List<PlatformNote> notes(String workspaceId, String contactId);
}
