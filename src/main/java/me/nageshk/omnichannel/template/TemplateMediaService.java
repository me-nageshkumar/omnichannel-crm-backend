package me.nageshk.omnichannel.template;

import com.mongodb.client.gridfs.model.GridFSFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Template media in the GridFS bucket {@code template_media}: type detection, brand scoping, copy and orphan sweep. */
@Service
public class TemplateMediaService {

    public static final long MAX_SIZE = 16L * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(TemplateMediaService.class);

    /** A stored file's details; {@code opus} is true for Ogg/Opus audio, the only kind a voice note may use. */
    public record Meta(MediaView view, boolean opus) {}

    /** A file ready to stream; the caller closes {@code resource}'s stream. */
    public record MediaFile(String fileName, String mimeType, long size, GridFsResource resource) {}

    private final GridFsTemplate gridFs;
    private final MongoTemplate mongo;
    private final Clock clock;

    public TemplateMediaService(@Qualifier("templateMediaGridFs") GridFsTemplate gridFs, MongoTemplate mongo,
            Clock clock) {
        this.gridFs = gridFs;
        this.mongo = mongo;
        this.clock = clock;
    }

    /** Stores an upload after checking its size and sniffing its type from the first bytes. */
    public MediaView upload(AuthenticatedUser me, String workspaceId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ValidationException("file", "is required");
        return store(me, workspaceId, file.getOriginalFilename(), file.getSize(), file::getInputStream);
    }

    /** Stores bytes the server holds (such as the bundled demo image) with the same checks as an upload. */
    public MediaView upload(AuthenticatedUser me, String workspaceId, String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new ValidationException("file", "is required");
        return store(me, workspaceId, fileName, bytes.length, () -> new ByteArrayInputStream(bytes));
    }

    /** A source that can be opened more than once: once to sniff the head, once to store. */
    private interface Source {
        InputStream open() throws IOException;
    }

    private MediaView store(AuthenticatedUser me, String workspaceId, String originalName, long size, Source source) {
        if (size > MAX_SIZE) throw new ValidationException("file", "is larger than 16 MB");
        String fileName = cleanName(originalName);
        byte[] head;
        try (InputStream in = source.open()) {
            head = in.readNBytes(MediaSniffer.HEAD);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String mime = MediaSniffer.detect(head, fileName)
                .orElseThrow(() -> new ValidationException("file", "is not a supported file type"));
        Document meta = metadata(workspaceId, fileName, mime, size, MediaSniffer.isOpus(head),
                me.getUsername(), Instant.now(clock));
        ObjectId id;
        try (InputStream in = source.open()) {
            id = gridFs.store(in, fileName, mime, meta);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new MediaView(id.toHexString(), fileName, mime, size);
    }

    /** The file for streaming; 404 when it does not exist in this brand. */
    public MediaFile open(String workspaceId, String mediaId) {
        GridFSFile f = find(workspaceId, mediaId).orElseThrow(() -> new NotFoundException("media not found"));
        Document m = f.getMetadata();
        return new MediaFile(m.getString("fileName"), m.getString("mimeType"), f.getLength(), gridFs.getResource(f));
    }

    /** The details of a brand's file, or empty when it does not exist in that brand. */
    public Optional<Meta> meta(String workspaceId, String mediaId) {
        return find(workspaceId, mediaId).map(f -> {
            Document m = f.getMetadata();
            return new Meta(new MediaView(f.getObjectId().toHexString(), m.getString("fileName"),
                    m.getString("mimeType"), f.getLength()), Boolean.TRUE.equals(m.getBoolean("opus")));
        });
    }

    /** An independent copy of the brand's file in the target brand; returns the new id. */
    public String copy(String workspaceId, String mediaId, String targetWorkspaceId) {
        GridFSFile f = find(workspaceId, mediaId).orElseThrow(() -> new NotFoundException("media not found"));
        Document m = f.getMetadata();
        Document meta = metadata(targetWorkspaceId, m.getString("fileName"), m.getString("mimeType"), f.getLength(),
                Boolean.TRUE.equals(m.getBoolean("opus")), m.getString("uploadedBy"), Instant.now(clock));
        try (InputStream in = gridFs.getResource(f).getInputStream()) {
            return gridFs.store(in, m.getString("fileName"), m.getString("mimeType"), meta).toHexString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deletes a file if it exists; a failure is logged, and the sweep removes the file later if it was orphaned. */
    public void delete(String mediaId) {
        ObjectId id = objectId(mediaId);
        if (id == null) return;
        try {
            gridFs.delete(new Query(Criteria.where("_id").is(id)));
        } catch (RuntimeException e) {
            log.warn("Could not delete template media {}", mediaId, e);
        }
    }

    /** Deletes files uploaded before the cutoff that no template refers to; returns how many were deleted. */
    public int sweepOrphans(Instant cutoff) {
        int deleted = 0;
        for (GridFSFile f : gridFs.find(new Query(Criteria.where("metadata.uploadedAt").lt(Date.from(cutoff))))) {
            String id = f.getObjectId().toHexString();
            if (mongo.exists(new Query(Criteria.where("media.mediaId").is(id)), MessageTemplate.class)) continue;
            gridFs.delete(new Query(Criteria.where("_id").is(f.getObjectId())));
            deleted++;
        }
        return deleted;
    }

    private Optional<GridFSFile> find(String workspaceId, String mediaId) {
        ObjectId id = objectId(mediaId);
        if (id == null) return Optional.empty();
        return Optional.ofNullable(gridFs.findOne(new Query(
                Criteria.where("_id").is(id).and("metadata.workspaceId").is(workspaceId))));
    }

    private static ObjectId objectId(String mediaId) {
        return mediaId != null && ObjectId.isValid(mediaId) ? new ObjectId(mediaId) : null;
    }

    private static Document metadata(String workspaceId, String fileName, String mime, long size, boolean opus,
            String uploadedBy, Instant at) {
        return new Document().append("workspaceId", workspaceId).append("fileName", fileName)
                .append("mimeType", mime).append("size", size).append("opus", opus)
                .append("uploadedBy", uploadedBy).append("uploadedAt", Date.from(at));
    }

    /** The file name without any directory part; "file" when nothing is left. */
    private static String cleanName(String original) {
        String n = original == null ? "" : original.strip();
        n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1).strip();
        if (n.length() > 255) n = n.substring(n.length() - 255);
        return n.isEmpty() ? "file" : n;
    }
}
