package me.nageshk.omnichannel.service.inbox.history;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import me.nageshk.omnichannel.common.api.NoDeviceException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.common.events.IngestionFailedException;
import me.nageshk.omnichannel.common.rpc.RpcException;
import me.nageshk.omnichannel.db.inbox.HistoryChatStatus;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.HistoryScopeKind;
import me.nageshk.omnichannel.db.inbox.HistorySkipReason;
import me.nageshk.omnichannel.db.inbox.HistoryWaiting;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportRepository;
import me.nageshk.omnichannel.service.inbox.InboxDevices;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import me.nageshk.omnichannel.service.inbox.InboxIngestService.ImportContext;
import me.nageshk.omnichannel.service.inbox.InboxIngestService.ImportOutcome;
import me.nageshk.omnichannel.service.inbox.InboxIngestService.ImportResult;
import me.nageshk.omnichannel.service.inbox.history.HistoryDeviceClient.ChatRow;
import me.nageshk.omnichannel.service.inbox.history.HistoryDeviceClient.FetchRequest;
import me.nageshk.omnichannel.service.inbox.history.HistoryDeviceClient.HistoryPage;
import me.nageshk.omnichannel.service.ws.DeviceSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Runs the active history imports, the oldest per number, on {@code historyImportScheduler}: claims each chat in turn
 * and walks its history backwards page by page, saving the cursor after every page, so a stop at any point (cancel,
 * disconnect, restart, time budget) resumes where it left off. One page is in flight per device. What a page brought
 * is stored, counted and announced before anything else is looked at, so a pause or a stop never loses a page that was
 * on its way: every page that stored something publishes {@code ChatChanged} and {@code HistoryStored}, and nothing
 * else is published.
 */
@Service
public class HistoryImportRunner {

    private static final Logger log = LoggerFactory.getLogger(HistoryImportRunner.class);
    private static final List<HistoryChatStatus> OPEN = List.of(HistoryChatStatus.PENDING, HistoryChatStatus.RUNNING);
    /** A chat's last-activity time is WhatsApp's own and unconfirmed, so a little earlier than the range is kept. */
    private static final Duration ACTIVITY_MARGIN = Duration.ofDays(1);
    /** What {@code getState} answers while WhatsApp Web is connected ({@code WAWebSocketModel.Socket.state}). */
    private static final String CONNECTED = "CONNECTED";

    private final MongoTemplate mongo;
    private final WaWebHistoryImportRepository imports;
    private final WaWebHistoryImportChatRepository items;
    private final WaWebChatRepository chats;
    private final InboxDevices devices;
    private final HistoryDeviceClient client;
    private final FetchedMessageImporter importer;
    private final HistoryImportCounter counter;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Executor executor;
    private final OmnichannelProperties.History settings;
    private final FetchedChatOpener opener;
    /** Numbers whose import of an earlier tick is still running. */
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean ticking = new AtomicBoolean();

    HistoryImportRunner(MongoTemplate mongo, WaWebHistoryImportRepository imports,
            WaWebHistoryImportChatRepository items, WaWebChatRepository chats, InboxDevices devices,
            HistoryDeviceClient client, FetchedMessageImporter importer, HistoryImportCounter counter,
            ApplicationEventPublisher events, Clock clock, @Qualifier("historyImportScheduler") Executor executor,
            OmnichannelProperties props, FetchedChatOpener opener) {
        this.mongo = mongo;
        this.imports = imports;
        this.items = items;
        this.chats = chats;
        this.devices = devices;
        this.client = client;
        this.importer = importer;
        this.counter = counter;
        this.events = events;
        this.clock = clock;
        this.executor = executor;
        this.settings = props.history();
        this.opener = opener;
    }

    // ---- the tick ----

    /**
     * Starts the oldest active import of each number whose earlier work has finished. The future completes when the
     * work started by this tick has finished; a tick while another is still starting does nothing.
     */
    public CompletableFuture<Void> tick() {
        if (!ticking.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
        try {
            Set<String> numbers = new HashSet<>();
            List<CompletableFuture<Void>> started = new ArrayList<>();
            for (WaWebHistoryImport imp : imports.findByActiveTrueOrderByCreationTimeAscIdAsc()) {
                // paused imports stay active, but must not hold up the number
                if (imp.getStatus() == HistoryImportStatus.PAUSED) continue;
                String number = imp.getPhoneNumberId();
                if (!numbers.add(number) || !busy.add(number)) continue;
                try {
                    started.add(CompletableFuture.runAsync(() -> {
                        try {
                            process(imp);
                        } finally {
                            busy.remove(number);
                        }
                    }, executor));
                } catch (RuntimeException rejected) {
                    busy.remove(number);
                    log.warn("could not start the history import {}", imp.getId(), rejected);
                }
            }
            return CompletableFuture.allOf(started.toArray(CompletableFuture[]::new));
        } catch (RuntimeException e) {
            log.warn("history import tick failed", e);
            return CompletableFuture.completedFuture(null);
        } finally {
            ticking.set(false);
        }
    }

    /** Chats left RUNNING by the last shutdown go back to PENDING, their cursor kept. */
    public int releaseStuck() {
        long released = mongo.updateMulti(new Query(Criteria.where("status").is(HistoryChatStatus.RUNNING)),
                new Update().set("status", HistoryChatStatus.PENDING).unset("attemptedAt"),
                WaWebHistoryImportChat.class).getModifiedCount();
        if (released > 0) log.info("released {} history import chats interrupted by the last shutdown", released);
        return (int) released;
    }

    // ---- one import ----

    void process(WaWebHistoryImport imp) {
        String id = imp.getId();
        try {
            // only this worker serves the number (busy), so a RUNNING chat here was left behind by an
            // earlier pass that failed to write (a DB blip) or by the last shutdown: back to PENDING, cursor kept
            mongo.updateMulti(new Query(Criteria.where("importId").is(id).and("status").is(HistoryChatStatus.RUNNING)),
                    new Update().set("status", HistoryChatStatus.PENDING), WaWebHistoryImportChat.class);
            DeviceSession device;
            try {
                device = devices.requireReady(imp.getWorkspaceId(), imp.getPhoneNumberId());
            } catch (NoDeviceException e) {
                setWaiting(id, waitingFor(e));
                return;
            } catch (NotFoundException e) {
                failImport(id, "NUMBER_GONE");
                return;
            }
            mongo.updateFirst(byIdIn(id, HistoryImportStatus.QUEUED),
                    new Update().set("status", HistoryImportStatus.RUNNING).set("startedAt", clock.instant()),
                    WaWebHistoryImport.class);
            mongo.updateFirst(byIdIn(id, HistoryImportStatus.RUNNING), new Update().set("waiting", null),
                    WaWebHistoryImport.class);
            if (!isRunning(id)) return;
            // a listing broken half way is done again: the chats it stored are kept, the rest added
            if (imp.getScope().kind() == HistoryScopeKind.ALL && imp.getListedAt() == null
                    && !listChats(imp, device)) {
                return;
            }
            Instant deadline = clock.instant().plus(settings.timeBudget());
            while (clock.instant().isBefore(deadline)) {
                // the extension may have reconnected (a new session) or gone since the last chat
                DeviceSession current;
                try {
                    current = devices.requireReady(imp.getWorkspaceId(), imp.getPhoneNumberId());
                } catch (NoDeviceException e) {
                    setWaiting(id, waitingFor(e));
                    break;
                } catch (NotFoundException e) {
                    failImport(id, "NUMBER_GONE");
                    return;
                }
                WaWebHistoryImportChat item = claim(id);
                if (item == null) break;
                ChatRun run;
                try {
                    run = importChat(imp, item, current);
                } finally {
                    counter.refresh(id);
                }
                // a chat read from what the browser already held asked nothing of WhatsApp: no pause after it
                if (!run.deviceOk() || run.paced() && !pause(settings.chatDelay()) || !isRunning(id)) break;
            }
            completeIfDone(id);
        } catch (RuntimeException e) {
            log.warn("history import {} failed this tick", id, e);
        }
    }

    /** One PENDING item per chat on the phone; false when the device failed or the import was failed. */
    private boolean listChats(WaWebHistoryImport imp, DeviceSession device) {
        List<ChatRow> rows;
        try {
            rows = client.listChats(device).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | RuntimeException e) {
            Throwable cause = unwrap(e);
            HistoryWaiting waiting = deviceFailure(cause);
            if (waiting != null) {
                setWaiting(imp.getId(), waiting);
                return false;
            }
            if (cause instanceof RpcException rpc) {
                failImport(imp.getId(), rpc.code());
                return false;
            }
            throw cause instanceof RuntimeException r ? r : new IllegalStateException(cause);
        }
        Long activeSince = activeSince(imp);
        int inactive = 0;
        for (ChatRow row : rows) {
            if (row.id() == null) continue;
            // nothing in it can be in range: most chats of a dated fetch, and each would cost a chatDelay
            if (activeSince != null && row.t() != null && row.t() > 0 && row.t() < activeSince) {
                inactive++;
                continue;
            }
            WaWebHistoryImportChat item = new WaWebHistoryImportChat();
            item.setImportId(imp.getId());
            item.setWorkspaceId(imp.getWorkspaceId());
            item.setPhoneNumberId(imp.getPhoneNumberId());
            item.setRemoteId(row.id());
            item.setChatName(row.name());
            item.setStatus(HistoryChatStatus.PENDING);
            item.setCounts(WaWebHistoryImportChat.Counts.ZERO);
            try {
                items.insert(item);
            } catch (DuplicateKeyException ignored) {
                // listed twice, or an earlier tick got this far
            }
        }
        mongo.updateFirst(new Query(Criteria.where("_id").is(imp.getId())),
                new Update().set("listedAt", clock.instant()), WaWebHistoryImport.class);
        log.info("history import {}: {} chats listed, {} left out as last active before the range", imp.getId(),
                rows.size(), inactive);
        counter.refresh(imp.getId());
        return true;
    }

    /**
     * The PENDING chat of the import attempted least recently (never attempted first: Mongo sorts a missing
     * {@code attemptedAt} first), now RUNNING; null when none is left. A chat that keeps timing out so goes to the
     * back instead of holding up the others.
     */
    private WaWebHistoryImportChat claim(String importId) {
        Query q = new Query(Criteria.where("importId").is(importId).and("status").is(HistoryChatStatus.PENDING))
                .with(Sort.by(Sort.Order.asc("attemptedAt"), Sort.Order.asc("_id")));
        Update u = new Update().set("status", HistoryChatStatus.RUNNING).set("attemptedAt", clock.instant());
        return mongo.findAndModify(q, u, FindAndModifyOptions.options().returnNew(true),
                WaWebHistoryImportChat.class);
    }

    /** A RUNNING import without a PENDING or RUNNING chat is COMPLETED; so is one paused on its last page. */
    private void completeIfDone(String importId) {
        if (items.existsByImportIdAndStatusIn(importId, OPEN)) return;
        boolean completed = mongo.updateFirst(new Query(Criteria.where("_id").is(importId).and("status")
                .in(HistoryImportStatus.RUNNING, HistoryImportStatus.PAUSED)),
                new Update().set("status", HistoryImportStatus.COMPLETED).set("active", false)
                        .set("finishedAt", clock.instant()).set("waiting", null),
                WaWebHistoryImport.class).getMatchedCount() > 0;
        if (completed) counter.refresh(importId);
    }

    // ---- one chat ----

    /**
     * How a chat's turn went: {@code deviceOk} false when the device failed and the import must stop for now;
     * {@code paced} when WhatsApp was (or may have been) asked for older messages, which the pause between chats is for.
     */
    record ChatRun(boolean deviceOk, boolean paced) {}

    /** Imports one claimed chat page by page. */
    ChatRun importChat(WaWebHistoryImport imp, WaWebHistoryImportChat item, DeviceSession device) {
        ImportContext ctx = new ImportContext(imp.getCustomerId(), imp.getWorkspaceId(), imp.getPhoneNumberId(),
                personName(item.getChatName()), item.getId());
        WaWebHistoryImport.Range range = imp.getRange();
        Long from = range == null || range.from() == null ? null : range.from().getEpochSecond();
        Long to = until(imp);
        WaWebHistoryImportChat.Cursor cursor = item.getCursor();
        WaWebHistoryImportChat.Counts start = item.getCounts() == null ? WaWebHistoryImportChat.Counts.ZERO
                : item.getCounts();
        long seen = start.seen();
        long inserted = start.inserted();
        long updated = start.updated();
        int emptyPages = item.getEmptyPages();
        // pages in a row that stored nothing, loaded or not: WhatsApp's word that it loaded cannot be all that holds us
        int idlePages = item.getIdlePages();
        // consecutive timeouts: a page that arrives resets the count
        int timeouts = item.getTimeouts();
        String createdChatId = item.getCreatedChatId();
        WaWebChat chat = null;
        // something was stored that the open consoles have not been told about
        boolean unannounced = false;
        // WhatsApp was asked for older messages, or a page timed out and may still be asking: the pause is for this
        boolean paced = false;
        try {
            while (true) {
                // never past the cap: the page holds the newest messages before the cursor
                int limit = (int) Math.max(1, Math.min(settings.pageSize(), settings.maxMessagesPerChat() - seen));
                HistoryPage page = client.fetchChatHistory(device, new FetchRequest(item.getRemoteId(),
                        cursor == null ? null : cursor.beforeT(), cursor == null ? List.of() : cursor.excludeIds(),
                        from, to, limit, settings.maxLoads())).get();
                // a page that leaves the cursor where it was holds what the last one held: it is no progress
                boolean moved = !page.messages().isEmpty() && !repeats(page, cursor);
                paced |= page.asked();
                if (moved) {
                    for (JsonNode m : page.messages()) {
                        ImportResult r = importer.importMessage(ctx, m);
                        seen++;
                        if (r.outcome() == ImportOutcome.INSERTED) inserted++;
                        else if (r.outcome() == ImportOutcome.UPDATED) updated++;
                        if (r.outcome() == ImportOutcome.INSERTED || r.outcome() == ImportOutcome.UPDATED) {
                            unannounced = true;
                        }
                        if (r.chat() != null) chat = r.chat();
                        if (r.chatCreated()) createdChatId = r.chat().getId();
                    }
                    cursor = new WaWebHistoryImportChat.Cursor(page.oldestT(), page.oldestIds());
                }
                // a page that only loaded walked further towards the range: only standing still counts
                emptyPages = moved || page.loaded() ? 0 : emptyPages + 1;
                idlePages = moved ? 0 : idlePages + 1;
                timeouts = 0;
                Update progress = new Update().set("cursor", cursor)
                        .set("counts", new WaWebHistoryImportChat.Counts(seen, inserted, updated))
                        .set("emptyPages", emptyPages).set("idlePages", idlePages).set("timeouts", 0);
                if (createdChatId != null) progress.set("createdChatId", createdChatId);
                if (page.unreadCount() != null) progress.set("phoneUnread", page.unreadCount());
                if (page.business() != null) progress.set("business", page.business());
                if (!whileOpen(item, progress)) return new ChatRun(true, paced);
                counter.refresh(imp.getId());
                if (unannounced) {
                    announce(item, chat);
                    unannounced = false;
                }
                if (!page.hasMore()) {
                    done(imp, item, new Update());
                    return new ChatRun(true, paced);
                }
                if (seen >= settings.maxMessagesPerChat()) {
                    done(imp, item, new Update().set("capped", true));
                    return new ChatRun(true, paced);
                }
                if (emptyPages >= settings.maxEmptyPages() || idlePages >= settings.maxIdlePages()) {
                    if (idlePages >= settings.maxIdlePages()) {
                        log.warn("history import chat {} stored nothing in {} pages, ending it", item.getId(), idlePages);
                    }
                    done(imp, item, new Update().set("olderUnavailable", true));
                    return new ChatRun(true, paced);
                }
                if (!pause(settings.pageDelay())) {
                    release(item);
                    return new ChatRun(false, paced);
                }
                // after the wait, which is where most pauses and stops land: no page is asked for after one
                if (!isRunning(imp.getId())) {
                    release(item);
                    return new ChatRun(true, paced);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            release(item);
            return new ChatRun(false, paced);
        } catch (ExecutionException | RuntimeException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof RpcException rpc && RpcException.CHAT_NOT_FOUND.equals(rpc.code())) {
                finish(item, new Update().set("status", HistoryChatStatus.SKIPPED)
                        .set("skipReason", HistorySkipReason.NO_CHAT));
                return new ChatRun(true, paced);
            }
            if (cause instanceof TimeoutException) {
                // the page may still be working on it: whatever comes next waits its turn
                paced = true;
                HistoryWaiting silent = silence(device);
                if (silent != null) {
                    // the page, or WhatsApp in it, answers nothing at all, not only this chat: wait, charging it nothing
                    release(item);
                    setWaiting(imp.getId(), silent);
                    return new ChatRun(false, paced);
                }
                int count = timeouts + 1;
                if (count >= settings.maxTimeoutsPerChat()) {
                    finish(item, new Update().set("status", HistoryChatStatus.FAILED).set("error", "TIMEOUT")
                            .set("timeouts", count));
                    return new ChatRun(true, paced);
                }
                whileOpen(item, new Update().set("status", HistoryChatStatus.PENDING).set("timeouts", count));
                setWaiting(imp.getId(), HistoryWaiting.WHATSAPP_NOT_READY);
                return new ChatRun(false, paced);
            }
            HistoryWaiting waiting = deviceFailure(cause);
            if (waiting != null) {
                release(item);
                setWaiting(imp.getId(), waiting);
                return new ChatRun(false, paced);
            }
            String error = cause instanceof RpcException rpc ? rpc.code()
                    : cause instanceof IngestionFailedException ? "DB_ERROR" : cause.getClass().getSimpleName();
            if (!(cause instanceof RpcException)) {
                log.warn("history import of chat {} failed", item.getId(), cause);
            }
            finish(item, new Update().set("status", HistoryChatStatus.FAILED).set("error", error)
                    .set("counts", new WaWebHistoryImportChat.Counts(seen, inserted, updated)));
            return new ChatRun(true, paced);
        } finally {
            // a page that failed half way stored what it stored
            if (unannounced) announce(item, chat);
        }
    }

    /** True when the import was stopped or failed: what it fetched stays, but it decides nothing more. */
    private boolean stopped(String importId) {
        WaWebHistoryImport now = mongo.findById(importId, WaWebHistoryImport.class);
        return now == null || now.getStatus() == HistoryImportStatus.CANCELLED
                || now.getStatus() == HistoryImportStatus.FAILED;
    }

    /**
     * Why the page cannot be asked anything right now, or null when it answers a cheap call with WhatsApp connected: then
     * a page that timed out is the chat's doing. A page that answers with an error is still there.
     */
    private HistoryWaiting silence(DeviceSession device) {
        try {
            JsonNode state = client.state(device).get();
            return state == null || !state.isString() || CONNECTED.equals(state.asString()) ? null
                    : HistoryWaiting.WHATSAPP_NOT_READY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HistoryWaiting.WHATSAPP_NOT_READY;
        } catch (ExecutionException | RuntimeException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof TimeoutException) return HistoryWaiting.WHATSAPP_NOT_READY;
            return deviceFailure(cause);
        }
    }

    /** True when the page ends where the cursor already is, which only a repeat of the last page can. */
    private static boolean repeats(HistoryPage page, WaWebHistoryImportChat.Cursor cursor) {
        if (cursor == null || !Objects.equals(cursor.beforeT(), page.oldestT())) return false;
        Set<String> seenIds = cursor.excludeIds() == null ? Set.of() : new HashSet<>(cursor.excludeIds());
        return seenIds.equals(new HashSet<>(page.oldestIds()));
    }

    private boolean finish(WaWebHistoryImportChat item, Update update) {
        return whileOpen(item, update.set("finishedAt", clock.instant()));
    }

    /**
     * Ends the chat DONE and, the first time only, opens it if it waits on us; not when the user stopped the import
     * while this chat's last page was on its way.
     */
    private void done(WaWebHistoryImport imp, WaWebHistoryImportChat item, Update update) {
        if (!finish(item, update.set("status", HistoryChatStatus.DONE))) return;
        WaWebHistoryImportChat ended = items.findById(item.getId()).orElse(null);
        if (ended == null || stopped(imp.getId())) return;
        if (ended.getCreatedChatId() == null) {
            // a crash between storing a page and noting its progress loses the note, not the chat's own mark
            chats.findFirstByCreatedByHistoryChatId(ended.getId()).ifPresent(created -> {
                ended.setCreatedChatId(created.getId());
                mongo.updateFirst(new Query(Criteria.where("_id").is(ended.getId())),
                        new Update().set("createdChatId", created.getId()), WaWebHistoryImportChat.class);
            });
        }
        try {
            opener.openIfWaiting(imp, ended);
        } catch (RuntimeException e) {
            log.warn("could not open fetched chat {}", item.getRemoteId(), e);
        }
    }

    /** Tells the open consoles the chat changed, and that an open thread has new messages to fetch. */
    private void announce(WaWebHistoryImportChat item, WaWebChat seen) {
        WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(item.getPhoneNumberId(), item.getRemoteId())
                .orElse(seen);
        if (chat == null) return;
        events.publishEvent(new InboxEvents.ChatChanged(chat));
        events.publishEvent(new InboxEvents.HistoryStored(chat));
    }

    /**
     * Applies the update unless the item has ended. A stop hands the item back while its page is still on the way, so
     * this does not ask for RUNNING: that page is stored, and its cursor and counts must be kept with it.
     */
    private boolean whileOpen(WaWebHistoryImportChat item, Update update) {
        boolean matched = mongo.updateFirst(new Query(Criteria.where("_id").is(item.getId()).and("status").in(OPEN)),
                update, WaWebHistoryImportChat.class).getMatchedCount() > 0;
        if (!matched) log.info("history import chat {} has ended", item.getId());
        return matched;
    }

    /** Back to PENDING with its cursor, for the next tick; {@code attemptedAt} stays, so it is claimed last. */
    private void release(WaWebHistoryImportChat item) {
        whileOpen(item, new Update().set("status", HistoryChatStatus.PENDING));
    }

    // ---- helpers ----

    /**
     * The newest second to import: the range's end, but never after the request. A message that arrived later is the
     * live path's, which counts it unread, reopens and routes the chat; stored here first it would be "existing" to it.
     */
    private static Long until(WaWebHistoryImport imp) {
        WaWebHistoryImport.Range range = imp.getRange();
        Long to = range == null || range.to() == null ? null : range.to().getEpochSecond();
        if (imp.getCreationTime() == null) return to;
        long created = imp.getCreationTime().getEpochSecond();
        return to == null ? created : Math.min(to, created);
    }

    /** Chats last active before this cannot hold anything in range; null when any chat can. */
    private static Long activeSince(WaWebHistoryImport imp) {
        WaWebHistoryImport.Range range = imp.getRange();
        if (range == null || range.from() == null) return null;
        return range.from().minus(ACTIVITY_MARGIN).getEpochSecond();
    }

    /** The chat's listed name, unless it is only a phone number, which WhatsApp lists when it knows no name. */
    private static String personName(String listed) {
        if (listed == null || listed.isBlank()) return null;
        // digits and no letters: WhatsApp formats a number with spacing and marks that vary by locale
        boolean number = listed.chars().anyMatch(Character::isDigit) && listed.chars().noneMatch(Character::isLetter);
        return number ? null : listed.strip();
    }

    private boolean isRunning(String importId) {
        return mongo.exists(byIdIn(importId, HistoryImportStatus.RUNNING), WaWebHistoryImport.class);
    }

    private void setWaiting(String importId, HistoryWaiting waiting) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(importId).and("active").is(true)),
                new Update().set("waiting", waiting), WaWebHistoryImport.class);
    }

    private void failImport(String importId, String error) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(importId).and("active").is(true)),
                new Update().set("status", HistoryImportStatus.FAILED).set("active", false).set("error", error)
                        .set("finishedAt", clock.instant()).set("waiting", null),
                WaWebHistoryImport.class);
    }

    private static Query byIdIn(String importId, HistoryImportStatus status) {
        return new Query(Criteria.where("_id").is(importId).and("status").is(status));
    }

    /** Sleeps between pages or chats; false when interrupted (the application is stopping). */
    private static boolean pause(Duration d) {
        if (d == null || d.isZero() || d.isNegative()) return true;
        try {
            Thread.sleep(d);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static HistoryWaiting waitingFor(NoDeviceException e) {
        return NoDeviceException.NO_DEVICE.equals(e.code()) ? HistoryWaiting.NO_DEVICE
                : HistoryWaiting.WHATSAPP_NOT_READY;
    }

    /** Why the device cannot serve pages now; null when the failure is not the device's. */
    private static HistoryWaiting deviceFailure(Throwable cause) {
        if (cause instanceof RpcException rpc) {
            // SEND_FAILED: the session closed between pages, the request never left
            if (RpcException.DEVICE_DISCONNECTED.equals(rpc.code()) || RpcException.SEND_FAILED.equals(rpc.code())) {
                return HistoryWaiting.NO_DEVICE;
            }
            if (RpcException.WHATSAPP_NOT_READY.equals(rpc.code())) return HistoryWaiting.WHATSAPP_NOT_READY;
            return null;
        }
        if (cause instanceof TimeoutException) return HistoryWaiting.WHATSAPP_NOT_READY;
        if (cause instanceof NoDeviceException e) return waitingFor(e);
        return null;
    }

    private static Throwable unwrap(Throwable e) {
        Throwable c = e;
        while ((c instanceof ExecutionException || c instanceof CompletionException) && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }
}
