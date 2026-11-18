package me.nageshk.omnichannel.ai.knowledge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.ai.connection.AiConnection;
import me.nageshk.omnichannel.ai.connection.AiConnectionRepository;
import me.nageshk.omnichannel.ai.connection.AiConnectionService;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeViews.BaseView;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeViews.NoteIn;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeViews.SaveBase;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeViews.SourceChange;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeViews.SourceView;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeViews.WebPageIn;
import me.nageshk.omnichannel.ai.profile.AiProfile;
import me.nageshk.omnichannel.ai.profile.AiProfileRepository;
import me.nageshk.omnichannel.ai.provider.AiCredential;
import me.nageshk.omnichannel.ai.provider.AiProvider;
import me.nageshk.omnichannel.ai.provider.AiProviderException;
import me.nageshk.omnichannel.ai.provider.AiProviders;
import me.nageshk.omnichannel.ai.provider.KnowledgeStoreProvider;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.DuplicateException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * A brand's knowledge bases and their sources. Sources are only stored here as QUEUED (with our copy in GridFS);
 * the background worker uploads them to the provider and tracks their state. The worker registers a waker so new or
 * changed sources are picked up at once.
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    static final int MAX_BASES = 20;
    static final int MAX_SOURCES = 200;
    static final int MAX_NOTE_CHARS = 50_000;
    static final String TYPES_MESSAGE = "PDF, DOCX, PPTX, TXT, MD or HTML only";
    private static final String MARKDOWN = "text/markdown";
    private static final Map<String, String> TYPES = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "txt", "text/plain",
            "md", MARKDOWN,
            "html", "text/html");

    /** A source's stored copy, opened for download. */
    public record Content(GridFsResource resource, KnowledgeSource source) {}

    private final KnowledgeBaseRepository bases;
    private final KnowledgeSourceRepository sources;
    private final AiConnectionRepository connections;
    private final AiConnectionService connectionService;
    private final AiProfileRepository profiles;
    private final AiProviders providers;
    private final GridFsTemplate files;
    private final MongoTemplate mongo;
    private final Clock clock;
    private volatile Runnable waker = () -> { };

    public KnowledgeService(KnowledgeBaseRepository bases, KnowledgeSourceRepository sources,
            AiConnectionRepository connections, AiConnectionService connectionService, AiProfileRepository profiles,
            AiProviders providers, @Qualifier("aiKnowledgeFilesGridFs") GridFsTemplate files, MongoTemplate mongo,
            Clock clock) {
        this.bases = bases;
        this.sources = sources;
        this.connections = connections;
        this.connectionService = connectionService;
        this.profiles = profiles;
        this.providers = providers;
        this.files = files;
        this.mongo = mongo;
        this.clock = clock;
    }

    /** The worker registers here; {@link #wake()} runs the callback after sources are added, changed or removed. */
    public void registerWaker(Runnable waker) {
        this.waker = Objects.requireNonNull(waker);
    }

    public void wake() {
        try {
            waker.run();
        } catch (RuntimeException e) {
            log.warn("The knowledge worker could not be woken: {}", e.toString());
        }
    }

    // ---- knowledge bases

    public List<BaseView> list(String ws) {
        Map<String, AiConnection> conns = connections.findByWorkspaceIdOrderByName(ws).stream()
                .collect(Collectors.toMap(AiConnection::getId, Function.identity()));
        Map<String, List<KnowledgeSource>> byBase = sources.findByWorkspaceId(ws).stream()
                .collect(Collectors.groupingBy(KnowledgeSource::getKnowledgeBaseId));
        List<AiProfile> all = profiles.findByWorkspaceIdOrderByName(ws);
        return bases.findByWorkspaceIdOrderByName(ws).stream()
                .map(b -> BaseView.of(b, conns.get(b.getConnectionId()), byBase.getOrDefault(b.getId(), List.of()),
                        names(all.stream().filter(p -> usesStore(p, b)).toList())))
                .toList();
    }

    public BaseView get(String ws, String id) {
        return view(findBase(ws, id));
    }

    public BaseView create(String ws, String customerId, String userId, SaveBase in) {
        Map<String, String> errors = validate(in);
        if (in.connectionId() == null || in.connectionId().isBlank()) errors.put("connectionId", "is required");
        if (!errors.isEmpty()) throw new ValidationException(errors);
        String name = in.name().trim();
        if (bases.findByWorkspaceIdAndName(ws, name).isPresent()) throw duplicate(name);
        if (bases.countByWorkspaceId(ws) >= MAX_BASES) {
            throw new CodedException(HttpStatus.CONFLICT, "ai_knowledge_limit", null, "At most 20 knowledge bases");
        }
        AiConnection conn = connections.findByIdAndWorkspaceId(in.connectionId(), ws)
                .orElseThrow(() -> new ValidationException("connectionId", "is not an AI connection of this brand"));
        KnowledgeStoreProvider provider = storeProvider(conn.getProvider());
        if (provider == null) {
            throw new CodedException(HttpStatus.BAD_REQUEST, "ai_knowledge_no_store_support", "connectionId",
                    "This provider cannot hold knowledge bases");
        }
        String storeId = providerCall(conn, () -> provider.createStore(credential(ws, conn), name));

        KnowledgeBase b = new KnowledgeBase();
        b.setWorkspaceId(ws);
        b.setCustomerId(customerId);
        b.setName(name);
        b.setDescription(description(in));
        b.setConnectionId(conn.getId());
        b.setProvider(conn.getProvider());
        b.setProviderStoreId(storeId);
        b.setStatus(KnowledgeBaseStatus.READY);
        b.setCreatedAt(clock.instant());
        b.setCreatedBy(userId);
        b.setUpdatedAt(b.getCreatedAt());
        try {
            return view(bases.save(b));
        } catch (DuplicateKeyException raced) {
            deleteStoreQuietly(ws, conn, provider, storeId);
            throw duplicate(name);
        }
    }

    public BaseView replace(String ws, String id, SaveBase in) {
        KnowledgeBase b = findBase(ws, id);
        Map<String, String> errors = validate(in);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        if (in.version() != null && !Objects.equals(in.version(), b.getVersion())) {
            throw new OptimisticLockingFailureException("Knowledge base changed since it was read");
        }
        String name = in.name().trim();
        if (!name.equals(b.getName())
                && bases.findByWorkspaceIdAndName(ws, name).filter(o -> !o.getId().equals(id)).isPresent()) {
            throw duplicate(name);
        }
        b.setName(name);
        b.setDescription(description(in));
        b.setUpdatedAt(clock.instant());
        try {
            return view(bases.save(b));
        } catch (DuplicateKeyException raced) {
            throw duplicate(name);
        }
    }

    public void delete(String ws, String id) {
        KnowledgeBase b = findBase(ws, id);
        List<String> using = names(profiles.findByWorkspaceIdAndVectorStoreIdsContaining(ws, b.getProviderStoreId()));
        if (!using.isEmpty()) {
            throw new CodedException(HttpStatus.CONFLICT, "ai_knowledge_in_use", null,
                    "Used by profiles: " + String.join(", ", using));
        }
        List<KnowledgeSource> rows = sources.findByKnowledgeBaseIdOrderByAddedAt(id);
        AiConnection conn = connections.findByIdAndWorkspaceId(b.getConnectionId(), ws).orElse(null);
        KnowledgeStoreProvider provider = conn == null ? null : storeProvider(conn.getProvider());
        if (provider != null) {
            providerCall(conn, () -> {
                provider.deleteStore(credential(ws, conn), b.getProviderStoreId());
                return null;
            });
            for (KnowledgeSource s : rows) {
                if (s.getProviderFileId() != null) deleteFileQuietly(ws, conn, provider, s.getProviderFileId());
                for (String replaced : s.getReplacedFileIds()) deleteFileQuietly(ws, conn, provider, replaced);
            }
        }
        files.delete(new Query(Criteria.where("metadata.workspaceId").is(ws).and("metadata.knowledgeBaseId").is(id)));
        mongo.remove(Query.query(Criteria.where("knowledgeBaseId").is(id)), KnowledgeSource.class);
        bases.delete(b);
    }

    // ---- sources

    public List<SourceView> sources(String ws, String id) {
        findBase(ws, id);
        return sources.findByKnowledgeBaseIdOrderByAddedAt(id).stream().map(SourceView::of).toList();
    }

    public SourceView addFile(String ws, String id, String userId, MultipartFile file) {
        KnowledgeBase b = findBase(ws, id);
        String fileName = baseName(file == null ? null : file.getOriginalFilename());
        if (file == null || file.isEmpty() || fileName.isBlank()) throw new ValidationException("file", "is required");
        String contentType = TYPES.get(extension(fileName));
        if (contentType == null) {
            throw new CodedException(HttpStatus.BAD_REQUEST, "ai_knowledge_file_type", "file", TYPES_MESSAGE);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ValidationException("file", "could not be read");
        }
        checkRoom(b);
        KnowledgeSource s = newSource(b, userId, SourceKind.FILE, fileName);
        s.setFileName(fileName);
        s.setContentType(contentType);
        s.setSize(bytes.length);
        return save(b, s, bytes, fileName, contentType);
    }

    public SourceView addWebPage(String ws, String id, String userId, WebPageIn in) {
        KnowledgeBase b = findBase(ws, id);
        String url = in == null || in.url() == null ? "" : in.url().strip();
        if (!isHttps(url)) throw new ValidationException("url", "must be an https address");
        checkRoom(b);
        KnowledgeSource s = newSource(b, userId, SourceKind.WEB_PAGE, url);
        s.setUrl(url);
        s.setContentType(MARKDOWN);
        sources.save(s);
        wake();
        return SourceView.of(s);
    }

    public SourceView addNote(String ws, String id, String userId, NoteIn in) {
        KnowledgeBase b = findBase(ws, id);
        Map<String, String> errors = validateNote(in == null ? null : in.title(), in == null ? null : in.text());
        if (!errors.isEmpty()) throw new ValidationException(errors);
        checkRoom(b);
        String title = in.title().trim();
        byte[] md = markdown(title, in.text());
        KnowledgeSource s = newSource(b, userId, SourceKind.NOTE, title);
        s.setContentType(MARKDOWN);
        s.setSize(md.length);
        return save(b, s, md, title + ".md", MARKDOWN);
    }

    /**
     * FETCH_AGAIN (web page), RETRY (failed source) or an edit of a note; each puts the source back in the queue. The
     * provider file in use moves to {@code replacedFileIds} and keeps answering until the worker has attached the new
     * one; if that fails, the old one stays attached.
     */
    public SourceView change(String ws, String id, String sourceId, SourceChange in) {
        KnowledgeBase b = findBase(ws, id);
        KnowledgeSource s = findSource(id, sourceId);
        if (s.getState() == SourceState.REMOVING) {
            throw new CodedException(HttpStatus.CONFLICT, "ai_knowledge_source_removing", null,
                    "The source is being removed");
        }
        String action = in == null ? null : in.action();
        String oldGridId = null;
        if (action == null) {
            if (s.getKind() != SourceKind.NOTE || in == null || (in.title() == null && in.text() == null)) {
                throw new ValidationException("action", "is required: FETCH_AGAIN or RETRY, or a title or text for a note");
            }
            String title = in.title() != null ? in.title() : s.getTitle();
            String text = in.text() != null ? in.text() : currentText(s);
            Map<String, String> errors = validateNote(title, text);
            if (!errors.isEmpty()) throw new ValidationException(errors);
            byte[] md = markdown(title.trim(), text);
            oldGridId = s.getGridFsId();
            s.setTitle(title.trim());
            s.setSize(md.length);
            s.setGridFsId(storeCopy(b, md, s.getTitle() + ".md", MARKDOWN));
        } else if (action.equals("FETCH_AGAIN")) {
            if (s.getKind() != SourceKind.WEB_PAGE) throw new ValidationException("action", "FETCH_AGAIN is for web pages");
        } else if (action.equals("RETRY")) {
            if (s.getState() != SourceState.FAILED) throw new ValidationException("action", "RETRY is for failed sources");
        } else {
            throw new ValidationException("action", "must be FETCH_AGAIN or RETRY");
        }
        // the file in use keeps answering until the worker has attached its replacement, then the worker drops it
        if (s.getProviderFileId() != null) {
            List<String> replaced = new ArrayList<>(s.getReplacedFileIds());
            replaced.add(s.getProviderFileId());
            s.setReplacedFileIds(replaced);
        }
        s.setProviderFileId(null);
        s.setState(SourceState.QUEUED);
        s.setError(null);
        s.setAttempts(0);
        s.setLeaseUntil(null);
        s.setNextCheckAt(null);
        KnowledgeSource saved;
        try {
            saved = sources.save(s);
        } catch (RuntimeException e) {
            if (oldGridId != null) deleteCopy(s.getGridFsId());
            throw e;
        }
        if (oldGridId != null) deleteCopy(oldGridId);
        wake();
        return SourceView.of(saved);
    }

    /** Marks the source REMOVING; the worker removes it from the provider and from our store. */
    public SourceView remove(String ws, String id, String sourceId) {
        findBase(ws, id);
        KnowledgeSource s = findSource(id, sourceId);
        if (s.getState() != SourceState.REMOVING) {
            s.setState(SourceState.REMOVING);
            s.setLeaseUntil(null);
            s.setNextCheckAt(null);
            s.setCheckDelaySeconds(0);
            s.setAttempts(0);
            s = sources.save(s);
            wake();
        }
        return SourceView.of(s);
    }

    public Content content(String ws, String id, String sourceId) {
        findBase(ws, id);
        KnowledgeSource s = findSource(id, sourceId);
        if (s.getGridFsId() == null) throw new NotFoundException("The source has no stored copy yet");
        var file = files.findOne(byGridId(s.getGridFsId()));
        if (file == null) throw new NotFoundException("The source has no stored copy");
        return new Content(files.getResource(file), s);
    }

    // ---- helpers

    private BaseView view(KnowledgeBase b) {
        AiConnection conn = connections.findByIdAndWorkspaceId(b.getConnectionId(), b.getWorkspaceId()).orElse(null);
        return BaseView.of(b, conn, sources.findByKnowledgeBaseIdOrderByAddedAt(b.getId()),
                names(profiles.findByWorkspaceIdAndVectorStoreIdsContaining(b.getWorkspaceId(), b.getProviderStoreId())));
    }

    private KnowledgeBase findBase(String ws, String id) {
        return bases.findByIdAndWorkspaceId(id, ws).orElseThrow(() -> new NotFoundException("Knowledge base not found"));
    }

    private KnowledgeSource findSource(String baseId, String sourceId) {
        return sources.findByIdAndKnowledgeBaseId(sourceId, baseId)
                .orElseThrow(() -> new NotFoundException("Source not found"));
    }

    private KnowledgeStoreProvider storeProvider(String providerId) {
        AiProvider p = providers.of(providerId);
        return p instanceof KnowledgeStoreProvider k ? k : null;
    }

    private AiCredential credential(String ws, AiConnection conn) {
        try {
            return connectionService.credential(ws, conn.getId());
        } catch (NotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CodedException(HttpStatus.BAD_GATEWAY, "ai_provider_error", null,
                    AiConnectionService.UNOPENABLE);
        }
    }

    /** Provider failures as the connection calls report them: a refused key or a provider error, both 502. */
    private <T> T providerCall(AiConnection conn, java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (AiProviderException e) {
            if (e.code() == AiProviderException.Code.AUTH) {
                throw new CodedException(HttpStatus.BAD_GATEWAY, "ai_provider_refused_key", null,
                        conn.getSettings().isEmpty() ? AiConnectionService.REFUSED
                                : AiConnectionService.REFUSED + " or the connection's settings");
            }
            throw new CodedException(HttpStatus.BAD_GATEWAY, "ai_provider_error", null, e.getMessage());
        }
    }

    private void deleteStoreQuietly(String ws, AiConnection conn, KnowledgeStoreProvider p, String storeId) {
        try {
            p.deleteStore(credential(ws, conn), storeId);
        } catch (RuntimeException e) {
            log.warn("Could not remove store {} after a failed create: {}", storeId, e.getMessage());
        }
    }

    private void deleteFileQuietly(String ws, AiConnection conn, KnowledgeStoreProvider p, String fileId) {
        try {
            p.deleteFile(credential(ws, conn), fileId);
        } catch (RuntimeException e) {
            log.warn("Could not delete provider file {}: {}", fileId, e.getMessage());
        }
    }

    private SourceView save(KnowledgeBase b, KnowledgeSource s, byte[] bytes, String fileName, String contentType) {
        s.setGridFsId(storeCopy(b, bytes, fileName, contentType));
        try {
            sources.save(s);
        } catch (RuntimeException e) {
            deleteCopy(s.getGridFsId());
            throw e;
        }
        wake();
        return SourceView.of(s);
    }

    private String storeCopy(KnowledgeBase b, byte[] bytes, String fileName, String contentType) {
        Document meta = new Document("workspaceId", b.getWorkspaceId()).append("knowledgeBaseId", b.getId());
        return files.store(new ByteArrayInputStream(bytes), fileName, contentType, meta).toHexString();
    }

    private void deleteCopy(String gridFsId) {
        if (gridFsId != null) files.delete(byGridId(gridFsId));
    }

    private static Query byGridId(String id) {
        return new Query(Criteria.where("_id").is(ObjectId.isValid(id) ? new ObjectId(id) : id));
    }

    private String currentText(KnowledgeSource s) {
        var file = s.getGridFsId() == null ? null : files.findOne(byGridId(s.getGridFsId()));
        if (file == null) return "";
        try {
            String md = new String(files.getResource(file).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String prefix = "# " + s.getTitle() + "\n\n";
            return md.startsWith(prefix) ? md.substring(prefix.length()) : md;
        } catch (IOException e) {
            throw new CodedException(HttpStatus.INTERNAL_SERVER_ERROR, "ai_knowledge_copy_unreadable", null,
                    "The stored copy could not be read");
        }
    }

    private KnowledgeSource newSource(KnowledgeBase b, String userId, SourceKind kind, String title) {
        KnowledgeSource s = new KnowledgeSource();
        s.setWorkspaceId(b.getWorkspaceId());
        s.setKnowledgeBaseId(b.getId());
        s.setKind(kind);
        s.setTitle(title);
        s.setState(SourceState.QUEUED);
        s.setAddedAt(clock.instant());
        s.setAddedBy(userId);
        return s;
    }

    private void checkRoom(KnowledgeBase b) {
        if (sources.countByKnowledgeBaseId(b.getId()) >= MAX_SOURCES) {
            throw new CodedException(HttpStatus.CONFLICT, "ai_knowledge_limit", null, "At most 200 sources");
        }
    }

    private static byte[] markdown(String title, String text) {
        return ("# " + title + "\n\n" + text).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> validateNote(String title, String text) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (title == null || title.isBlank()) errors.put("title", "is required");
        else if (title.trim().length() > 200) errors.put("title", "must be at most 200 characters");
        if (text == null || text.isBlank()) errors.put("text", "is required");
        else if (text.length() > MAX_NOTE_CHARS) errors.put("text", "must be at most 50,000 characters");
        return errors;
    }

    private static Map<String, String> validate(SaveBase in) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (in == null || in.name() == null || in.name().isBlank()) errors.put("name", "is required");
        else if (in.name().trim().length() > 100) errors.put("name", "must be at most 100 characters");
        if (in != null && in.description() != null && in.description().length() > 500) {
            errors.put("description", "must be at most 500 characters");
        }
        return errors;
    }

    private static String description(SaveBase in) {
        return in.description() == null ? "" : in.description().trim();
    }

    private static boolean isHttps(String url) {
        if (url.isEmpty() || url.length() > 2000) return false;
        try {
            URI u = new URI(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && !u.getHost().isBlank();
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    private static String baseName(String name) {
        if (name == null) return "";
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).strip();
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return ext.equals("htm") ? "html" : ext;
    }

    private static boolean usesStore(AiProfile p, KnowledgeBase b) {
        return p.getVectorStoreIds() != null && p.getVectorStoreIds().contains(b.getProviderStoreId());
    }

    private static List<String> names(List<AiProfile> ps) {
        return ps.stream().map(AiProfile::getName).sorted().toList();
    }

    private static DuplicateException duplicate(String name) {
        return new DuplicateException("name", "A knowledge base named '" + name + "' already exists");
    }
}
