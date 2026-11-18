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
    private static final int MAX_REMOVE_BACKOFF_SECONDS = 300;
    private static final int RETRY_REFUSED_SECONDS = 600;
    /** A busy provider while adding or indexing: the row waits 5 s, doubling, or as long as the provider asked. */
    private static final int FIRST_RETRY_SECONDS = 5;
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
                // backed off like a busy provider: never a hot loop that logs this every tick
                if (s.getState() == SourceState.REMOVING) stillRemoving(s, null, backoff(s));
                else if (s.getState() == SourceState.READY) replacedLeft(s, Leftover.BUSY);
                else retryOrFail(s, "The source could not be processed", null);
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
        if (s.getState() == SourceState.REMOVING) {
            remove(s, base, conn, provider);
            return;
        }
        if (s.getState() == SourceState.READY) {
            dropReplacedOfReady(s, base, conn, provider);
            return;
        }
        AiCredential cred = null;
        if (provider != null) {
            try {
                cred = connectionService.credential(s.getWorkspaceId(), conn.getId());
            } catch (RuntimeException e) {
                fail(s, AiConnectionService.UNOPENABLE);
                return;
            }
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
            if (s.getState() == SourceState.INDEXING) check(s, base, conn, provider, cred);
            else upload(s, base, conn, provider, cred);
        } catch (AiProviderException e) {
            providerFailure(s, conn, e);
        } catch (WebPageReader.WebPageException e) {
            fail(s, e.getMessage());
        }
    }

    /**
     * QUEUED or UPLOADING: read the page if it is one, upload our copy, attach it, then wait for indexing. Files the
     * row replaced are detached once the new one is attached; until then they keep answering, and a failure here
     * leaves them so.
     */
    private void upload(KnowledgeSource s, KnowledgeBase base, AiConnection conn, KnowledgeStoreProvider provider,
            AiCredential cred) {
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
            s = sources.save(s);
            saved = true;
            deleteCopy(oldCopy);
            if (!s.getReplacedFileIds().isEmpty()) {
                // one still attached is tried again at the indexing checks
                dropReplaced(s, base, conn, provider, cred);
                sources.save(s);
            }
        } finally {
            if (!saved) {
                deleteCopy(newCopy);
                if (fileId != null) dropProviderFile(base, provider, cred, fileId);
            }
        }
    }

    /** INDEXING and due: ask the provider where the file stands. Replaced files still attached are tried again. */
    private void check(KnowledgeSource s, KnowledgeBase base, AiConnection conn, KnowledgeStoreProvider provider,
            AiCredential cred) {
        KnowledgeFileState st = provider.fileState(cred, base.getProviderStoreId(), s.getProviderFileId());
        Instant now = clock.instant();
        switch (st.kind()) {
            case READY -> {
                s.setState(SourceState.READY);
                s.setError(null);
                s.setLeaseUntil(null);
                s.setNextCheckAt(null);
                s.setAttempts(0);
                if (s.getReplacedFileIds().isEmpty()) {
                    sources.save(s);
                } else {
                    s.setCheckDelaySeconds(0);
                    replacedLeft(s, dropReplaced(s, base, conn, provider, cred));
                }
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
                if (!s.getReplacedFileIds().isEmpty()) dropReplaced(s, base, conn, provider, cred);
                sources.save(s);
            }
        }
    }

    /**
     * REMOVING: the row stays until its file, and any file it replaced, is detached from the store (already detached
     * counts), however long that takes. A provider that is busy is asked again after 2 s, doubling to 5 minutes; a
     * refused or unopenable key, or a rejected request, shows its reason on the row and is tried again every 10
     * minutes. Once detached, deleting a file is best effort.
     */
    private void remove(KnowledgeSource s, KnowledgeBase base, AiConnection conn, KnowledgeStoreProvider provider) {
        if (s.getProviderFileId() == null && s.getReplacedFileIds().isEmpty() || conn == null || provider == null) {
            removeLocal(s);
            return;
        }
        AiCredential cred;
        try {
            cred = connectionService.credential(s.getWorkspaceId(), conn.getId());
        } catch (RuntimeException e) {
            stillRemoving(s, AiConnectionService.UNOPENABLE, RETRY_REFUSED_SECONDS);
            return;
        }
        if (s.getProviderFileId() != null) {
            Leftover left = detachAndDelete(s, base, conn, provider, cred, s.getProviderFileId());
            if (left != null) {
                stillRemoving(s, left);
                return;
            }
            s.setProviderFileId(null);
        }
        Leftover left = dropReplaced(s, base, conn, provider, cred);
        if (left != null) {
            stillRemoving(s, left);
            return;
        }
        removeLocal(s);
    }

    private void stillRemoving(KnowledgeSource s, Leftover left) {
        stillRemoving(s, left.reason(), left.reason() == null ? backoff(s) : RETRY_REFUSED_SECONDS);
    }

    private void stillRemoving(KnowledgeSource s, String reason, int delaySeconds) {
        s.setError(reason);
        s.setLeaseUntil(null);
        s.setNextCheckAt(clock.instant().plusSeconds(delaySeconds));
        s.setCheckDelaySeconds(reason == null ? delaySeconds : 0);
        sources.save(s);
    }

    /** The next wait of a busy provider while removing or replacing: 2 s, doubling to 5 minutes. */
    private static int backoff(KnowledgeSource s) {
        return s.getCheckDelaySeconds() <= 0 ? FIRST_CHECK_SECONDS
                : Math.min(MAX_REMOVE_BACKOFF_SECONDS, s.getCheckDelaySeconds() * 2);
    }

    // ---- files a change replaced

    /** Why a file is still attached: the provider was busy ({@code reason} null), or refused (the reason shown). */
    private record Leftover(String reason) {
        static final Leftover BUSY = new Leftover(null);
    }

    /**
     * Detaches and deletes, oldest first, the files the row replaced; each one gone leaves the row's list (the caller
     * saves the row). Null when none is left, else why the next one stays.
     */
    private Leftover dropReplaced(KnowledgeSource s, KnowledgeBase base, AiConnection conn,
            KnowledgeStoreProvider provider, AiCredential cred) {
        List<String> left = new ArrayList<>(s.getReplacedFileIds());
        try {
            while (!left.isEmpty()) {
                Leftover stays = detachAndDelete(s, base, conn, provider, cred, left.getFirst());
                if (stays != null) return stays;
                left.removeFirst();
            }
            return null;
        } finally {
            s.setReplacedFileIds(left);
        }
    }

    /** Null once the file is detached (deleting it is best effort), else why it is still attached. */
    private Leftover detachAndDelete(KnowledgeSource s, KnowledgeBase base, AiConnection conn,
            KnowledgeStoreProvider provider, AiCredential cred, String fileId) {
        try {
            provider.detach(cred, base.getProviderStoreId(), fileId);
        } catch (AiProviderException e) {
            if (TRANSIENT.contains(e.code())) return Leftover.BUSY;
            if (e.code() == Code.AUTH) {
                connectionService.markInvalid(s.getWorkspaceId(), conn.getId(), AiConnectionService.REFUSED);
                return new Leftover(AiConnectionService.REFUSED);
            }
            return new Leftover(e.getMessage());
        }
        try {
            provider.deleteFile(cred, fileId);
        } catch (RuntimeException e) {
            log.warn("Could not delete provider file {}: {}", fileId, e.getMessage());
        }
        return null;
    }

    /** READY with replaced files still attached, and due: try them again. */
    private void dropReplacedOfReady(KnowledgeSource s, KnowledgeBase base, AiConnection conn,
            KnowledgeStoreProvider provider) {
        if (conn == null || provider == null) {
            // the connection is gone: nothing can ever detach them
            s.setReplacedFileIds(new ArrayList<>());
            replacedLeft(s, null);
            return;
        }
        AiCredential cred;
        try {
            cred = connectionService.credential(s.getWorkspaceId(), conn.getId());
        } catch (RuntimeException e) {
            replacedLeft(s, new Leftover(AiConnectionService.UNOPENABLE));
            return;
        }
        replacedLeft(s, dropReplaced(s, base, conn, provider, cred));
    }

    /**
     * Saves a READY row after an attempt on its replaced files: done, or asked again as a removal is (busy: 2 s
     * doubling to 5 minutes; refused: every 10 minutes with the reason on the row).
     */
    private void replacedLeft(KnowledgeSource s, Leftover left) {
        s.setLeaseUntil(null);
        s.setAttempts(0);
        if (left == null) {
            s.setError(null);
            s.setNextCheckAt(null);
            s.setCheckDelaySeconds(0);
        } else if (left.reason() == null) {
            int delay = backoff(s);
            s.setError(null);
            s.setCheckDelaySeconds(delay);
            s.setNextCheckAt(clock.instant().plusSeconds(delay));
        } else {
            s.setError(left.reason());
            s.setCheckDelaySeconds(0);
            s.setNextCheckAt(clock.instant().plusSeconds(RETRY_REFUSED_SECONDS));
        }
        sources.save(s);
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
            retryOrFail(s, e.getMessage(), e.retryAfter());
        } else {
            fail(s, e.getMessage());
        }
    }

    /**
     * The row is claimed again after a wait until its attempts are used up: 5 s after the first attempt, doubling, or
     * as long as the provider asked when that is longer. The lease holds it off meanwhile.
     */
    private void retryOrFail(KnowledgeSource s, String message, java.time.Duration retryAfter) {
        if (s.getAttempts() >= props.attempts()) {
            fail(s, message);
            return;
        }
        long wait = (long) FIRST_RETRY_SECONDS << Math.min(10, Math.max(0, s.getAttempts() - 1));
        if (retryAfter != null && retryAfter.getSeconds() > wait) wait = retryAfter.getSeconds();
        s.setLeaseUntil(clock.instant().plusSeconds(wait));
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
