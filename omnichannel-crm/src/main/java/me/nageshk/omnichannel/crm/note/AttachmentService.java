package me.nageshk.omnichannel.crm.note;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.common.CrmErrors;
import me.nageshk.omnichannel.crm.note.dto.NoteView;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Files on notes, kept in GridFS: at most five per note, ten megabytes each, documents and images only. */
@Service
public class AttachmentService {

    public static final int MAX_FILES = 5;
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_NAME = 200;
    private static final List<String> EXACT = List.of("application/pdf", "text/plain", "text/csv", "application/msword",
            "application/vnd.ms-excel", "application/vnd.ms-powerpoint");
    private static final List<String> PREFIXES = List.of("image/", "application/vnd.openxmlformats-officedocument.");

    /** What a download needs: the stored file and how to name it. */
    public record Download(GridFsResource resource, NoteAttachment attachment) {}

    private final NoteService notes;
    private final NoteFiles files;
    private final Clock clock;

    AttachmentService(NoteService notes, NoteFiles files, Clock clock) {
        this.notes = notes;
        this.files = files;
        this.clock = clock;
    }

    public NoteView add(CrmContext ctx, String noteId, MultipartFile file) {
        CrmNote note = notes.editable(ctx, noteId);
        if (file == null || file.isEmpty()) throw rejected("No file was sent");
        if (note.getAttachments() != null && note.getAttachments().size() >= MAX_FILES)
            throw rejected("A note holds at most " + MAX_FILES + " files");
        if (file.getSize() > MAX_BYTES) throw rejected("Files may be at most 10 MB");
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT).trim();
        if (!allowed(type)) throw rejected("Only images, PDF, Office documents, CSV and plain text can be attached");
        String name = fileName(file.getOriginalFilename());
        String fileId;
        try (InputStream in = file.getInputStream()) {
            fileId = files.store(ctx.workspaceId(), note, name, type, in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        NoteAttachment a = new NoteAttachment(UUID.randomUUID().toString(), name, type, file.getSize(), fileId,
                ctx.userId(), Instant.now(clock));
        try {
            return notes.views(List.of(notes.guarded(ctx, noteId, note.getVersion(), new Update().push("attachments", a))))
                    .get(0);
        } catch (RuntimeException e) {
            files.delete(ctx.workspaceId(), fileId); // the note changed meanwhile: do not leave the file behind
            throw e;
        }
    }

    public NoteView remove(CrmContext ctx, String noteId, String attachmentId) {
        CrmNote note = notes.editable(ctx, noteId);
        NoteAttachment a = find(note, attachmentId);
        CrmNote saved = notes.guarded(ctx, noteId, note.getVersion(),
                new Update().pull("attachments", new org.bson.Document("id", attachmentId)));
        files.delete(ctx.workspaceId(), a.fileId());
        return notes.views(List.of(saved)).get(0);
    }

    public Download open(CrmContext ctx, String noteId, String attachmentId) {
        CrmNote note = notes.load(ctx, noteId);
        NoteAttachment a = find(note, attachmentId);
        GridFsResource r = files.open(ctx.workspaceId(), a.fileId());
        if (r == null || !r.exists()) throw new NotFoundException("File not found");
        return new Download(r, a);
    }

    private static NoteAttachment find(CrmNote note, String attachmentId) {
        return (note.getAttachments() == null ? List.<NoteAttachment>of() : note.getAttachments()).stream()
                .filter(x -> x.id().equals(attachmentId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Attachment not found"));
    }

    static boolean allowed(String type) {
        return EXACT.contains(type) || PREFIXES.stream().anyMatch(type::startsWith);
    }

    /** The last path segment, without control characters, at most 200 characters; "file" when nothing is left. */
    static String fileName(String original) {
        String n = original == null ? "" : original;
        n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1);
        n = n.replaceAll("\\p{Cntrl}", "").strip();
        if (n.equals(".") || n.equals("..")) n = "";
        if (n.length() > MAX_NAME) n = n.substring(n.length() - MAX_NAME);
        return n.isEmpty() ? "file" : n;
    }

    private static RuntimeException rejected(String message) {
        return CrmErrors.badRequest("ATTACHMENT_REJECTED", message);
    }
}
