package me.nageshk.omnichannel.ai.knowledge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import me.nageshk.omnichannel.ai.config.AiProperties;
import me.nageshk.omnichannel.ai.connection.AiConnection;
import me.nageshk.omnichannel.ai.connection.AiConnectionRepository;
import me.nageshk.omnichannel.ai.connection.AiConnectionService;
import me.nageshk.omnichannel.ai.provider.AiCredential;
import me.nageshk.omnichannel.ai.provider.AiProviderException;
import me.nageshk.omnichannel.ai.provider.AiProviderException.Code;
import me.nageshk.omnichannel.ai.provider.AiProviders;
import me.nageshk.omnichannel.ai.provider.KnowledgeFileState;
import me.nageshk.omnichannel.ai.provider.KnowledgeStoreProvider;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Moves knowledge sources through their states on the provider: uploads a queued source (reading a web page first),
 * checks that the provider has indexed it, and removes sources marked REMOVING. Rows are claimed from
 * {@link KnowledgeQueue} under a lease and run on the worker's own pool. Every save of a row carries its version, so a
 * change made through the API while the worker holds the row wins; the worker then takes back what it just made at
 * the provider (spec: nothing is left orphaned).
 */
@Service
public class KnowledgeWorker {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeWorker.class);
    private static final int FIRST_CHECK_SECONDS = 2;
    private static final int LAST_CHECK_SECONDS = 30;
    private static final Set<Code> TRANSIENT = Set.of(Code.RATE_LIMIT, Code.UNAVAILABLE, Code.TIMEOUT);

    private final KnowledgeQueue queue;
    private final KnowledgeSourceRepository sources;
    private final KnowledgeBaseRepository bases;
    private final AiConnectionRepository connections;
    private final AiConnectionService connectionService;
    private final AiProviders providers;
    private final GridFsTemplate files;
    private final WebPageReader reader;
    private final ThreadPoolTaskExecutor pool;
    private final AiProperties.Knowledge props;
    private final Clock clock;

    public KnowledgeWorker(KnowledgeQueue queue, KnowledgeSourceRepository sources, KnowledgeBaseRepository bases,
            AiConnectionRepository connections, AiConnectionService connectionService, AiProviders providers,
            @Qualifier("aiKnowledgeFilesGridFs") GridFsTemplate files, WebPageReader reader,
            @Qualifier("aiKnowledgeExecutor") ThreadPoolTaskExecutor pool, AiProperties props, Clock clock,
            KnowledgeService service) {
        this.queue = queue;
        this.sources = sources;
        this.bases = bases;
        this.connections = connections;
        this.connectionService = connectionService;
        this.providers = providers;
        this.files = files;
        this.reader = reader;
        this.pool = pool;
        this.props = props.knowledge();
        this.clock = clock;
        if (props.autoTick()) service.registerWaker(this::tick);
    }

    /**
     * Claims sources while a pool thread is free for each and runs them there. The future completes when those runs are
     * done (tests wait on it; the schedule does not). Overlapping ticks take turns.
     */
    public synchronized CompletableFuture<Void> tick() {
        List<CompletableFuture<Void>> work = new ArrayList<>();
        try {
            while (threadFree()) {
                Optional<KnowledgeSource> claimed = queue.claim(clock.instant());
                if (claimed.isEmpty()) break;
                KnowledgeSource s = claimed.get();
                work.add(CompletableFuture.runAsync(() -> process(s), pool));
            }
        } catch (RejectedExecutionException e) {
            log.warn("The knowledge pool refused work; a claimed source is claimed again when its lease runs out");
        } catch (RuntimeException e) {
            log.warn("Knowledge tick failed: {}", e.toString());
        }
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
    }

    private boolean threadFree() {
        return pool.getActiveCount() + pool.getQueueSize() < pool.getMaxPoolSize();
    }

    // ---- one claimed source

    private void process(KnowledgeSource s) {
        try {
            run(s);
        } catch (OptimisticLockingFailureException e) {
            log.info("Knowledge source {} changed while it was worked on; the change wins", s.getId());
        } catch (RuntimeException e) {
            log.warn("Knowledge source {} failed unexpectedly", s.getId(), e);
            try {
                retryOrFail(s, "The source could not be processed");
            } catch (RuntimeException again) {
                log.warn("Knowledge source {} could not be saved: {}", s.getId(), again.toString());
            }
        }
    }

    private void run(KnowledgeSource s) {
        KnowledgeBase base = bases.findById(s.getKnowledgeBaseId()).orElse(null);
        if (base == null) {
            removeLocal(s);
            return;
        }
        AiConnection conn = connections.findByIdAndWorkspaceId(base.getConnectionId(), s.getWorkspaceId()).orElse(null);
        KnowledgeStoreProvider provider = conn != null && providers.of(conn.getProvider()) instanceof KnowledgeStoreProvider k
                ? k : null;
        AiCredential cred = null;
        if (provider != null) {
            try {
                cred = connectionService.credential(s.getWorkspaceId(), conn.getId());
            } catch (RuntimeException e) {
                if (s.getState() != SourceState.REMOVING) {
                    fail(s, AiConnectionService.UNOPENABLE);
                    return;
                }
            }
        }
        if (s.getState() == SourceState.REMOVING) {
            remove(s, base, conn, cred == null ? null : provider, cred);
            return;
        }
        if (provider == null || cred == null) {
            fail(s, "The AI connection of this knowledge base is gone");
            return;
        }
        if (s.getAttempts() > props.attempts() + 1) {
            fail(s, "The source was cut off too many times (a restart or a crash)");
            return;
        }
        try {
            if (s.getState() == SourceState.INDEXING) check(s, base, provider, cred);
            else upload(s, base, provider, cred);
        } catch (AiProviderException e) {
            providerFailure(s, conn, e);
        } catch (WebPageReader.WebPageException e) {
            fail(s, e.getMessage());
        }
    }

    /** QUEUED or UPLOADING: read the page if it is one, upload our copy, attach it, then wait for indexing. */
    private void upload(KnowledgeSource s, KnowledgeBase base, KnowledgeStoreProvider provider, AiCredential cred) {
        String newCopy = null;
        String fileId = null;
        boolean saved = false;
        try {
            byte[] bytes;
            String fileName;
            String oldCopy = null;
            WebPageReader.Page page = null;
            if (s.getKind() == SourceKind.WEB_PAGE) {
                page = reader.read(address(s.getUrl()));
                bytes = page.markdown().getBytes(StandardCharsets.UTF_8);
                newCopy = storeCopy(base, bytes, fileNameOf(page.title()), "text/markdown");
                oldCopy = s.getGridFsId();
                fileName = fileNameOf(page.title());
            } else {
                bytes = readCopy(s.getGridFsId());
                if (bytes == null) {
                    fail(s, "The stored copy of this source is missing");
                    return;
                }
                fileName = s.getKind() == SourceKind.NOTE ? s.getTitle() + ".md" : s.getFileName();
            }
            fileId = provider.uploadFile(cred, fileName, s.getContentType(), bytes);
            provider.attach(cred, base.getProviderStoreId(), fileId);

            Instant now = clock.instant();
            if (page != null) {
                s.setTitle(page.title());
                s.setGridFsId(newCopy);
                s.setSize(bytes.length);
                s.setFetchedAt(now);
            }
            s.setProviderFileId(fileId);
            s.setState(SourceState.INDEXING);
            s.setError(null);
            s.setAttempts(0);
            s.setLeaseUntil(null);
            s.setIndexingSince(now);
            s.setCheckDelaySeconds(FIRST_CHECK_SECONDS);
            s.setNextCheckAt(now.plusSeconds(FIRST_CHECK_SECONDS));
            sources.save(s);
            saved = true;
            deleteCopy(oldCopy);
        } finally {
            if (!saved) {
                deleteCopy(newCopy);
                if (fileId != null) dropProviderFile(base, provider, cred, fileId);
            }
        }
    }

    /** INDEXING and due: ask the provider where the file stands. */
    private void check(KnowledgeSource s, KnowledgeBase base, KnowledgeStoreProvider provider, AiCredential cred) {
        KnowledgeFileState st = provider.fileState(cred, base.getProviderStoreId(), s.getProviderFileId());
        Instant now = clock.instant();
        switch (st.kind()) {
            case READY -> {
                s.setState(SourceState.READY);
                s.setError(null);
                s.setLeaseUntil(null);
                s.setNextCheckAt(null);
                s.setAttempts(0);
                sources.save(s);
            }
            case FAILED -> fail(s, st.error() == null || st.error().isBlank() ? "Indexing failed" : st.error());
            case INDEXING -> {
                Instant since = s.getIndexingSince() == null ? now : s.getIndexingSince();
                if (!now.isBefore(since.plus(props.giveUpAfter()))) {
                    fail(s, "OpenAI did not finish indexing in " + props.giveUpAfter().toMinutes() + " minutes");
                    return;
                }
                int delay = Math.min(LAST_CHECK_SECONDS, Math.max(FIRST_CHECK_SECONDS, s.getCheckDelaySeconds()) * 2);
                s.setCheckDelaySeconds(delay);
                s.setNextCheckAt(now.plusSeconds(delay));
                s.setLeaseUntil(null);
                s.setAttempts(0);
                sources.save(s);
            }
        }
    }

    /** REMOVING: detach and delete the provider file, then our copy and the row. A provider that stays out of reach is given up on. */
    private void remove(KnowledgeSource s, KnowledgeBase base, AiConnection conn, KnowledgeStoreProvider provider,
            AiCredential cred) {
        if (provider != null && s.getProviderFileId() != null) {
            try {
                provider.detach(cred, base.getProviderStoreId(), s.getProviderFileId());
                provider.deleteFile(cred, s.getProviderFileId());
            } catch (AiProviderException e) {
                if (e.code() == Code.AUTH) {
                    connectionService.markInvalid(s.getWorkspaceId(), conn.getId(), AiConnectionService.REFUSED);
                }
                if (TRANSIENT.contains(e.code()) && s.getAttempts() < props.attempts()) {
                    s.setLeaseUntil(null);
                    sources.save(s);
                    return;
                }
                log.warn("Provider file {} could not be removed ({}); removing the source anyway", s.getProviderFileId(),
                        e.getMessage());
            }
        }
        removeLocal(s);
    }

    private void removeLocal(KnowledgeSource s) {
        deleteCopy(s.getGridFsId());
        sources.deleteById(s.getId());
    }

    // ---- outcomes

    private void providerFailure(KnowledgeSource s, AiConnection conn, AiProviderException e) {
        if (e.code() == Code.AUTH) {
            connectionService.markInvalid(s.getWorkspaceId(), conn.getId(), AiConnectionService.REFUSED);
            fail(s, AiConnectionService.REFUSED);
        } else if (TRANSIENT.contains(e.code())) {
            retryOrFail(s, e.getMessage());
        } else {
            fail(s, e.getMessage());
        }
    }

    /** The row stays for the next claim until its attempts are used up. */
    private void retryOrFail(KnowledgeSource s, String message) {
        if (s.getAttempts() >= props.attempts()) {
            fail(s, message);
            return;
        }
        s.setLeaseUntil(null);
        sources.save(s);
    }

    private void fail(KnowledgeSource s, String message) {
        s.setState(SourceState.FAILED);
        s.setError(message);
        s.setLeaseUntil(null);
        s.setNextCheckAt(null);
        sources.save(s);
    }

    // ---- helpers

    private static URI address(String url) {
        try {
            return new URI(url);
        } catch (java.net.URISyntaxException | NullPointerException e) {
            throw new WebPageReader.WebPageException("This address is not allowed");
        }
    }

    private static String fileNameOf(String title) {
        String slug = title.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 60) slug = slug.substring(0, 60);
        return (slug.isEmpty() ? "page" : slug) + ".md";
    }

    private String storeCopy(KnowledgeBase b, byte[] bytes, String fileName, String contentType) {
        org.bson.Document meta = new org.bson.Document("workspaceId", b.getWorkspaceId()).append("knowledgeBaseId", b.getId());
        return files.store(new ByteArrayInputStream(bytes), fileName, contentType, meta).toHexString();
    }

    private byte[] readCopy(String gridFsId) {
        if (gridFsId == null) return null;
        var file = files.findOne(byGridId(gridFsId));
        if (file == null) return null;
        try {
            return files.getResource(file).getInputStream().readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("The stored copy could not be read", e);
        }
    }

    private void deleteCopy(String gridFsId) {
        if (gridFsId != null) files.delete(byGridId(gridFsId));
    }

    private static Query byGridId(String id) {
        return new Query(Criteria.where("_id").is(ObjectId.isValid(id) ? new ObjectId(id) : id));
    }

    /** Best effort: a file made at the provider for a save that lost is detached and deleted again. */
    private void dropProviderFile(KnowledgeBase base, KnowledgeStoreProvider provider, AiCredential cred, String fileId) {
        try {
            provider.detach(cred, base.getProviderStoreId(), fileId);
        } catch (RuntimeException e) {
            log.warn("Could not detach provider file {}: {}", fileId, e.getMessage());
        }
        try {
            provider.deleteFile(cred, fileId);
        } catch (RuntimeException e) {
            log.warn("Could not delete provider file {}: {}", fileId, e.getMessage());
        }
    }
}
