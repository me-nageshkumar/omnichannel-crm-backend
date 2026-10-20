package me.nageshk.omnichannel.inbox;

import com.mongodb.client.gridfs.model.GridFSFile;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.rpc.RpcDispatcher;
import me.nageshk.omnichannel.rpc.RpcException;
import me.nageshk.omnichannel.ws.DeviceSession;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Media of inbox messages in the GridFS bucket {@code chat_media}: stored when a console send carries media, and
 * fetched from the phone the first time someone opens an inbound one (spec section 7).
 */
@Service
public class ChatMediaService {

    public static final long MAX_SIZE = 16L * 1024 * 1024;

    private static final String FALLBACK_TYPE = "application/octet-stream";

    /** A stored file ready to stream; the caller closes {@code resource}'s stream. */
    public record MediaFile(String fileName, String mimeType, long size, GridFsResource resource) {
    }

    private final GridFsTemplate gridFs;
    private final InboxDevices devices;
    private final RpcDispatcher dispatcher;
    private final WaWebMessageRepository messages;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ChatMediaService(@Qualifier("chatMediaGridFs") GridFsTemplate gridFs, InboxDevices devices,
            RpcDispatcher dispatcher, WaWebMessageRepository messages, ApplicationEventPublisher publisher,
            ObjectMapper mapper, Clock clock) {
        this.gridFs = gridFs;
        this.devices = devices;
        this.dispatcher = dispatcher;
        this.messages = messages;
        this.publisher = publisher;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Stores the bytes of a message's media; returns the media id. */
    public String store(String workspaceId, String phoneNumberId, String messageId, String mimetype, String filename,
            byte[] data) {
        String type = mimeOrFallback(mimetype);
        String name = filename == null || filename.isBlank() ? "file" : filename;
        Document meta = new Document().append("workspaceId", workspaceId).append("phoneNumberId", phoneNumberId)
                .append("messageId", messageId).append("mimeType", type).append("fileName", name)
                .append("storedAt", Date.from(clock.instant()));
        return gridFs.store(new ByteArrayInputStream(data), name, type, meta).toHexString();
    }

    /** @throws NotFoundException if the file does not exist in this workspace */
    public MediaFile open(String workspaceId, String mediaId) {
        if (mediaId == null || !ObjectId.isValid(mediaId)) throw new NotFoundException("media not found");
        GridFSFile f = gridFs.findOne(new Query(
                Criteria.where("_id").is(new ObjectId(mediaId)).and("metadata.workspaceId").is(workspaceId)));
        if (f == null) throw new NotFoundException("media not found");
        Document m = f.getMetadata();
        return new MediaFile(m.getString("fileName"), m.getString("mimeType"), f.getLength(), gridFs.getResource(f));
    }

    /** Deletes every file of the number. */
    public void deleteByPhoneNumber(String phoneNumberId) {
        gridFs.delete(new Query(Criteria.where("metadata.phoneNumberId").is(phoneNumberId)));
    }

    /**
     * The message's media: from the cache when it has been fetched before, else from the phone, which is then
     * cached and announced to the consoles ({@code available = true}).
     *
     * @throws NotFoundException if the message has no media or its file is missing
     * @throws me.nageshk.omnichannel.api.NoDeviceException if the file must be fetched and the device is not ready
     * @throws MediaUnavailableException (in the future) if the phone no longer has the media or it is over 16 MB
     */
    public CompletableFuture<MediaFile> fetchIfNeeded(WaWebChat chat, WaWebMessage message) {
        WaWebMessage.Media media = message.getMedia();
        if (media == null) throw new NotFoundException("message has no media");
        if (media.mediaId() != null) return CompletableFuture.completedFuture(open(chat.getWorkspaceId(), media.mediaId()));

        DeviceSession device = devices.requireReady(chat.getWorkspaceId(), chat);
        return dispatcher.call(device, "downloadMedia", mapper.createObjectNode().put("messageId", message.getWaMessageId()))
                .thenApply(result -> cache(chat, message, result))
                .exceptionally(e -> {
                    Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof RpcException r && RpcException.MEDIA_UNAVAILABLE.equals(r.code())) {
                        throw new MediaUnavailableException();
                    }
                    if (cause instanceof RuntimeException re) throw re;
                    throw new CompletionException(cause);
                });
    }

    private MediaFile cache(WaWebChat chat, WaWebMessage message, JsonNode result) {
        byte[] data = decode(result);
        WaWebMessage current = messages.findById(message.getId()).orElse(message);
        WaWebMessage.Media was = current.getMedia() == null ? message.getMedia() : current.getMedia();
        if (was.mediaId() != null) return open(chat.getWorkspaceId(), was.mediaId());
        String mimetype = was.mimetype() != null ? was.mimetype() : text(result, "mimetype");
        String filename = was.filename() != null ? was.filename() : text(result, "filename");
        String mediaId = store(chat.getWorkspaceId(), chat.getPhoneNumberId(), message.getId(), mimetype, filename,
                data);
        Instant now = clock.instant();
        current.setMedia(new WaWebMessage.Media(mimetype == null ? null : mimeOrFallback(mimetype), filename,
                was.size() != null ? was.size() : Long.valueOf(data.length), mediaId, now));
        current = messages.save(current);
        publisher.publishEvent(new InboxEvents.MessageUpserted(chat, current, false));
        return open(chat.getWorkspaceId(), mediaId);
    }

    private static byte[] decode(JsonNode result) {
        String data = text(result, "data");
        if (data == null || data.isEmpty() || data.length() > MAX_SIZE / 3 * 4 + 8) throw new MediaUnavailableException();
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new MediaUnavailableException();
        }
        if (bytes.length > MAX_SIZE) throw new MediaUnavailableException();
        return bytes;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode v = node.get(field);
        return v == null || !v.isString() || v.asString().isBlank() ? null : v.asString();
    }

    /** The type if it parses as a media type, else {@code application/octet-stream}. */
    private static String mimeOrFallback(String mimetype) {
        if (mimetype == null || mimetype.isBlank()) return FALLBACK_TYPE;
        try {
            MediaType.parseMediaType(mimetype);
            return mimetype;
        } catch (RuntimeException e) {
            return FALLBACK_TYPE;
        }
    }
}
