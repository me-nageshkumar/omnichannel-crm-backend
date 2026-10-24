package me.nageshk.omnichannel.crm.note;

import java.time.Instant;
import org.springframework.data.mongodb.core.mapping.Field;

/** A file on a note, stored in the {@code crmNoteFiles} GridFS bucket under {@code fileId}. */
public record NoteAttachment(@Field("id") String id, String fileName, String contentType, long size, String fileId,
        String uploadedBy, Instant uploadedAt) {}
