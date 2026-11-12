package me.nageshk.omnichannel.service.cloud;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import com.mongodb.client.gridfs.model.GridFSFile;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMediaInfo;
import me.nageshk.omnichannel.common.channel.whatsapp.message.MessagingProduct;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.template.MediaSniffer;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Files uploaded for Cloud message headers, in the GridFS bucket {@code cloudMedia}: sniffed, capped per header type,
 * brand-scoped, and swept once no message page refers to them (spec §5.6).
 */
@Service
public class CloudMediaService {

    /** What an upload stored. */
    public record Stored(String id, String mimeType, long size, String sha256) {}

    /** A file ready to stream; the caller closes {@code resource}'s stream. */
    public record MediaFile(String mimeType, long size, GridFsResource resource) {}

    private static final String PAGES = "messagePages";
    private static final Set<String> HEADER_TYPES = Set.of("image", "video", "document");

    private final GridFsTemplate gridFs;
    private final MongoTemplate mongo;
    private final Clock clock;

    public CloudMediaService(@Qualifier("cloudMediaGridFs") GridFsTemplate gridFs, MongoTemplate mongo, Clock clock) {
        this.gridFs = gridFs;
        this.mongo = mongo;
        this.clock = clock;
    }

    /** Stores an upload meant for a header of {@code headerType} ({@code image}, {@code video} or {@code document}). */
    public Stored upload(AuthenticatedUser me, String workspaceId, String headerType, MultipartFile file) {
        if (!HEADER_TYPES.contains(headerType == null ? "" : headerType)) {
            throw new ValidationException("type", "must be image, video or document");
        }
        if (file == null || file.isEmpty()) throw new ValidationException("file", "is required");
        long max = CloudMediaTypes.maxBytes(headerType);
        if (file.getSize() > max) throw new ValidationException("file", "is larger than " + (max / (1024 * 1024)) + " MB");
        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] head = bytes.length > MediaSniffer.HEAD ? java.util.Arrays.copyOf(bytes, MediaSniffer.HEAD) : bytes;
        String mime = MediaSniffer.detect(head, file.getOriginalFilename())
                .orElseThrow(() -> new ValidationException("file", "is not a supported file type"));
        if (!CloudMediaTypes.forHeader(headerType).contains(mime)) {
            throw new ValidationException("file", "must be " + CloudMediaTypes.describe(headerType));
        }
        String sha256 = sha256(bytes);
        Document meta = new Document().append("workspaceId", workspaceId).append("uploadedBy", me.getUsername())
                .append("mimeType", mime).append("size", (long) bytes.length).append("sha256", sha256)
                .append("uploadedAt", Date.from(Instant.now(clock)));
        ObjectId id = gridFs.store(new ByteArrayInputStream(bytes), "header", mime, meta);
        return new Stored(id.toHexString(), mime, bytes.length, sha256);
    }

    /** The MIME type of a brand's file, or empty when it does not exist in that brand. */
    public Optional<String> mimeOf(String workspaceId, String mediaId) {
        return find(workspaceId, mediaId).map(f -> f.getMetadata().getString("mimeType"));
    }

    /** The file for streaming; 404 when it does not exist in this brand. */
    public MediaFile open(String workspaceId, String mediaId) {
        GridFSFile f = find(workspaceId, mediaId).orElseThrow(() -> new NotFoundException("media not found"));
        return new MediaFile(f.getMetadata().getString("mimeType"), f.getLength(), gridFs.getResource(f));
    }

    /** The Cloud {@code GET /media/{id}} shape; {@code url} is where the bytes are served. */
    public CloudMediaInfo info(String workspaceId, String mediaId, String url) {
        GridFSFile f = find(workspaceId, mediaId).orElseThrow(() -> new NotFoundException("media not found"));
        Document m = f.getMetadata();
        return new CloudMediaInfo(MessagingProduct.WHATSAPP_WEB, url, m.getString("mimeType"), m.getString("sha256"),
                f.getLength(), mediaId);
    }

    /** Deletes files uploaded before the cutoff that no message page's header refers to; returns how many. */
    public int sweepOrphans(Instant cutoff) {
        int deleted = 0;
        for (GridFSFile f : gridFs.find(new Query(Criteria.where("metadata.uploadedAt").lt(Date.from(cutoff))))) {
            String id = f.getObjectId().toHexString();
            Query referenced = new Query(new Criteria().orOperator(
                    Criteria.where("message.interactive.header.image.id").is(id),
                    Criteria.where("message.interactive.header.video.id").is(id),
                    Criteria.where("message.interactive.header.document.id").is(id)));
            if (mongo.exists(referenced, PAGES)) continue;
            gridFs.delete(new Query(Criteria.where("_id").is(f.getObjectId())));
            deleted++;
        }
        return deleted;
    }

    private Optional<GridFSFile> find(String workspaceId, String mediaId) {
        if (mediaId == null || !ObjectId.isValid(mediaId)) return Optional.empty();
        return Optional.ofNullable(gridFs.findOne(new Query(
                Criteria.where("_id").is(new ObjectId(mediaId)).and("metadata.workspaceId").is(workspaceId))));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
