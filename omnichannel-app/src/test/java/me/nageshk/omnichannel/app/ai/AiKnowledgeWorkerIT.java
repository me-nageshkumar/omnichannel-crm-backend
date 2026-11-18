package me.nageshk.omnichannel.app.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import me.nageshk.omnichannel.ai.connection.AiConnection;
import me.nageshk.omnichannel.ai.connection.AiConnectionStatus;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeBase;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeService;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeSource;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeViews.SourceChange;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeWorker;
import me.nageshk.omnichannel.ai.knowledge.SourceKind;
import me.nageshk.omnichannel.ai.knowledge.SourceState;
import me.nageshk.omnichannel.app.support.OpenAiStub.Recorded;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.HttpMethod;

/** The knowledge worker ticked by hand against the OpenAI stub: upload, indexing checks, failures and removal. */
class AiKnowledgeWorkerIT extends AiTestBase {

    private static final String KEY = "sk-test-1234567890k77Z";

    @Autowired KnowledgeWorker worker;
    @Autowired KnowledgeService knowledge;
    @Autowired @Qualifier("aiKnowledgeFilesGridFs") GridFsTemplate files;

    private HttpServer pages;
    private String pageBody = "<html><head><title>Fees</title></head><body><h1>Fees</h1><p>Online ₹19,999</p></body></html>";
    private int pageStatus = 200;
    private final AtomicInteger pageHits = new AtomicInteger();
    private String kbId;
    private String connId;

    @Override
    protected List<Class<?>> collections() {
        List<Class<?>> c = new ArrayList<>(super.collections());
        c.add(KnowledgeBase.class);
        c.add(KnowledgeSource.class);
        return c;
    }

    @BeforeEach
    void setUp() throws IOException {
        files.delete(new Query());
        pages = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        pages.createContext("/", ex -> {
            pageHits.incrementAndGet();
            byte[] out = pageBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(pageStatus, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        pages.start();
        Map<String, Object> conn = new LinkedHashMap<>();
        conn.put("name", "Main " + COUNTER.incrementAndGet());
        conn.put("provider", "openai");
        conn.put("apiKey", KEY);
        connId = ai(HttpMethod.POST, "/connections", manager, conn).getBody().get("id").asString();
        OPENAI.enqueue(200, "{\"id\":\"vs_1\"}");
        kbId = ai(HttpMethod.POST, "/knowledge-bases", manager,
                Map.of("name", "Courses " + COUNTER.incrementAndGet(), "description", "", "connectionId", connId))
                .getBody().get("id").asString();
        OPENAI.reset();
    }

    @AfterEach
    void stopPages() {
        pages.stop(0);
    }

    private void tick() throws Exception {
        worker.tick().get(30, TimeUnit.SECONDS);
    }

    private String note(String title, String text) {
        return ai(HttpMethod.POST, "/knowledge-bases/" + kbId + "/sources/note", manager,
                Map.of("title", title, "text", text)).getBody().get("id").asString();
    }

    private String webPage() {
        KnowledgeSource s = new KnowledgeSource();
        s.setWorkspaceId(brand.getId());
        s.setKnowledgeBaseId(kbId);
        s.setKind(SourceKind.WEB_PAGE);
        s.setUrl("http://127.0.0.1:" + pages.getAddress().getPort() + "/fees");
        s.setTitle(s.getUrl());
        s.setContentType("text/markdown");
        s.setState(SourceState.QUEUED);
        s.setAddedAt(clock.instant());
        return mongo.insert(s).getId();
    }

    private KnowledgeSource row(String id) {
        return mongo.findById(id, KnowledgeSource.class);
    }

    private String gridText(KnowledgeSource s) throws IOException {
        var file = files.findOne(new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(s.getGridFsId()))));
        return new String(files.getResource(file).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private List<String> calls() {
        return OPENAI.requests().stream().map(r -> r.method() + " " + r.path()).toList();
    }

    /** Upload and attach of the first tick: file_1, attached. */
    private void uploaded(String id) throws Exception {
        OPENAI.enqueue(200, "{\"id\":\"file_1\"}");
        OPENAI.enqueue(200, "{}");
        tick();
        assertThat(row(id).getState()).isEqualTo(SourceState.INDEXING);
    }

    @Test
    void noteGoesQueuedToReady() throws Exception {
        String id = note("Pitch", "Say hello");
        OPENAI.enqueue(200, "{\"id\":\"file_1\"}");
        OPENAI.enqueue(200, "{}");
        OPENAI.enqueue(200, "{\"status\":\"in_progress\"}");
        OPENAI.enqueue(200, "{\"status\":\"completed\"}");

        tick();
        KnowledgeSource s = row(id);
        assertThat(s.getState()).isEqualTo(SourceState.INDEXING);
        assertThat(s.getProviderFileId()).isEqualTo("file_1");
        assertThat(s.getNextCheckAt()).isEqualTo(clock.instant().plusSeconds(2));
        assertThat(s.getIndexingSince()).isEqualTo(clock.instant());
        assertThat(s.getAttempts()).isZero();
        assertThat(s.getLeaseUntil()).isNull();

        tick();
        assertThat(OPENAI.requests()).hasSize(2);

        clock.advance(Duration.ofSeconds(2));
        tick();
        s = row(id);
        assertThat(s.getState()).isEqualTo(SourceState.INDEXING);
        assertThat(s.getNextCheckAt()).isEqualTo(clock.instant().plusSeconds(4));

        clock.advance(Duration.ofSeconds(4));
        tick();
        s = row(id);
        assertThat(s.getState()).isEqualTo(SourceState.READY);
        assertThat(s.getError()).isNull();
        assertThat(calls()).containsExactly("POST /v1/files", "POST /v1/vector_stores/vs_1/files",
                "GET /v1/vector_stores/vs_1/files/file_1", "GET /v1/vector_stores/vs_1/files/file_1");
        assertThat(OPENAI.requests().get(0).body()).contains("Pitch.md", "Say hello");
        assertThat(OPENAI.requests().get(1).body()).contains("file_1");
    }

    @Test
    void indexingFailedEndsFailedWithReason() throws Exception {
        String id = note("Pitch", "text");
        uploaded(id);
        clock.advance(Duration.ofSeconds(2));
        OPENAI.enqueue(200, "{\"status\":\"failed\",\"last_error\":{\"code\":\"unsupported_file\","
                + "\"message\":\"File type not supported\"}}");

        tick();

        KnowledgeSource s = row(id);
        assertThat(s.getState()).isEqualTo(SourceState.FAILED);
        assertThat(s.getError()).isEqualTo("File type not supported");
        assertThat(s.getProviderFileId()).isEqualTo("file_1");
    }

    @Test
    void givesUpAfter30Minutes() throws Exception {
        String id = note("Pitch", "text");
        uploaded(id);
        clock.advance(Duration.ofMinutes(29));
        OPENAI.enqueue(200, "{\"status\":\"in_progress\"}");
        tick();
        KnowledgeSource s = row(id);
        assertThat(s.getState()).isEqualTo(SourceState.INDEXING);
        assertThat(s.getNextCheckAt()).isEqualTo(clock.instant().plusSeconds(4));

        clock.advance(Duration.ofMinutes(2));
        OPENAI.enqueue(200, "{\"status\":\"in_progress\"}");
        tick();

        s = row(id);
        assertThat(s.getState()).isEqualTo(SourceState.FAILED);
        assertThat(s.getError()).isEqualTo("OpenAI did not finish indexing in 30 minutes");
    }

    @Test
    void pollingSlowsToEvery30Seconds() throws Exception {
        String id = note("Pitch", "text");
        uploaded(id);
        long[] expected = {4, 8, 16, 30, 30};
        for (long next : expected) {
            clock.advance(Duration.ofSeconds(31));
            OPENAI.enqueue(200, "{\"status\":\"in_progress\"}");
            tick();
            assertThat(row(id).getNextCheckAt()).isEqualTo(clock.instant().plusSeconds(next));
        }
    }

    @Test
    void transientErrorRetriesThenFails() throws Exception {
        String id = note("Pitch", "text");
        for (int i = 0; i < 3; i++) OPENAI.enqueue(503, "{\"error\":{\"message\":\"Server busy\"}}");

        tick();
        assertThat(row(id).getState()).isEqualTo(SourceState.QUEUED);
        assertThat(row(id).getAttempts()).isEqualTo(1);
        tick();
        assertThat(row(id).getState()).isEqualTo(SourceState.QUEUED);
        tick();

        KnowledgeSource s = row(id);
        assertThat(s.getState()).isEqualTo(SourceState.FAILED);
        assertThat(s.getError()).contains("Server busy");
        assertThat(OPENAI.requests()).hasSize(3);
    }

    @Test
    void aTransientErrorThenSuccessCarriesOn() throws Exception {
        String id = note("Pitch", "text");
        OPENAI.enqueue(503, "{\"error\":{\"message\":\"Server busy\"}}");
        tick();
        assertThat(row(id).getState()).isEqualTo(SourceState.QUEUED);

        uploaded(id);
        clock.advance(Duration.ofSeconds(2));
        OPENAI.enqueue(200, "{\"status\":\"completed\"}");
        tick();

        assertThat(row(id).getState()).isEqualTo(SourceState.READY);
    }

    @Test
    void authFailureMarksConnectionInvalid() throws Exception {
        String id = note("Pitch", "text");
        OPENAI.enqueue(401, "{\"error\":{\"message\":\"bad key\"}}");

        tick();

        KnowledgeSource s = row(id);
        assertThat(s.getState()).isEqualTo(SourceState.FAILED);
        assertThat(s.getError()).isEqualTo("The provider refused the key");
        assertThat(mongo.findById(connId, AiConnection.class).getStatus()).isEqualTo(AiConnectionStatus.INVALID);
    }

    @Test
    void badRequestFailsWithTheMessage() throws Exception {
        String id = note("Pitch", "text");
        OPENAI.enqueue(400, "{\"error\":{\"message\":\"Unsupported file\"}}");

        tick();

        assertThat(row(id).getState()).isEqualTo(SourceState.FAILED);
        assertThat(row(id).getError()).contains("Unsupported file");
        assertThat(OPENAI.requests()).hasSize(1);
    }

    @Test
    void removingDeletesEverywhere() throws Exception {
        String id = note("Pitch", "text");
        uploaded(id);
        String grid = row(id).getGridFsId();
        knowledge.remove(brand.getId(), kbId, id);
        assertThat(row(id).getState()).isEqualTo(SourceState.REMOVING);
        OPENAI.reset();

        tick();

        assertThat(calls()).containsExactly("DELETE /v1/vector_stores/vs_1/files/file_1", "DELETE /v1/files/file_1");
        assertThat(row(id)).isNull();
        assertThat(files.findOne(new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(grid))))).isNull();
    }

    @Test
    void expiredLeaseIsClaimedAgain() throws Exception {
        String id = note("Pitch", "text");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new org.springframework.data.mongodb.core.query.Update().set("state", SourceState.UPLOADING)
                        .set("leaseUntil", clock.instant().plusSeconds(60)).set("attempts", 1), KnowledgeSource.class);
        tick();
        assertThat(OPENAI.requests()).isEmpty();
        assertThat(row(id).getState()).isEqualTo(SourceState.UPLOADING);

        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new org.springframework.data.mongodb.core.query.Update().set("leaseUntil", clock.instant().minusSeconds(1)),
                KnowledgeSource.class);
        OPENAI.enqueue(200, "{\"id\":\"file_1\"}");
        OPENAI.enqueue(200, "{}");
        tick();

        assertThat(row(id).getState()).isEqualTo(SourceState.INDEXING);
    }

    @Test
    void webPageIsFetchedStoredAndUploaded() throws Exception {
        String id = webPage();

        uploaded(id);

        KnowledgeSource s = row(id);
        assertThat(s.getTitle()).isEqualTo("Fees");
        assertThat(s.getFetchedAt()).isEqualTo(clock.instant());
        assertThat(gridText(s)).startsWith("Source: http://127.0.0.1:").contains("# Fees", "Online ₹19,999");
        assertThat(s.getSize()).isEqualTo(gridText(s).getBytes(StandardCharsets.UTF_8).length);
        assertThat(OPENAI.requests().get(0).body()).contains("Online ₹19,999");
    }

    @Test
    void webPageThatAnswersAnErrorFails() throws Exception {
        String id = webPage();
        pageStatus = 404;

        tick();

        assertThat(row(id).getState()).isEqualTo(SourceState.FAILED);
        assertThat(row(id).getError()).isEqualTo("The page answered 404");
        assertThat(OPENAI.requests()).isEmpty();
    }

    @Test
    void fetchAgainReplacesTheProviderFile() throws Exception {
        String id = webPage();
        uploaded(id);
        clock.advance(Duration.ofSeconds(2));
        OPENAI.enqueue(200, "{\"status\":\"completed\"}");
        tick();
        KnowledgeSource first = row(id);
        assertThat(first.getState()).isEqualTo(SourceState.READY);
        pageBody = pageBody.replace("19,999", "24,999");
        OPENAI.reset();

        knowledge.change(brand.getId(), kbId, id, new SourceChange(null, null, "FETCH_AGAIN"));
        assertThat(calls()).containsExactly("DELETE /v1/vector_stores/vs_1/files/file_1", "DELETE /v1/files/file_1");
        assertThat(row(id).getState()).isEqualTo(SourceState.QUEUED);
        assertThat(row(id).getProviderFileId()).isNull();
        OPENAI.reset();
        OPENAI.enqueue(200, "{\"id\":\"file_2\"}");
        OPENAI.enqueue(200, "{}");
        OPENAI.enqueue(200, "{\"status\":\"completed\"}");

        tick();
        clock.advance(Duration.ofSeconds(2));
        tick();

        KnowledgeSource s = row(id);
        assertThat(s.getState()).isEqualTo(SourceState.READY);
        assertThat(s.getProviderFileId()).isEqualTo("file_2");
        assertThat(gridText(s)).contains("24,999");
        assertThat(gridText(s)).doesNotContain("19,999");
        assertThat(files.find(new Query(Criteria.where("metadata.knowledgeBaseId").is(kbId)))).hasSize(1);
        assertThat(pageHits.get()).isEqualTo(2);
    }

    @Test
    void editDuringUploadLeavesNoOrphanFile() throws Exception {
        String id = note("Pitch", "first text");
        String firstGrid = row(id).getGridFsId();
        OPENAI.beforeNextRequest(() ->
                knowledge.change(brand.getId(), kbId, id, new SourceChange(null, "second text", null)));
        OPENAI.enqueue(200, "{\"id\":\"file_1\"}");
        OPENAI.enqueue(200, "{}");

        tick();

        assertThat(calls()).containsExactly("POST /v1/files", "POST /v1/vector_stores/vs_1/files",
                "DELETE /v1/vector_stores/vs_1/files/file_1", "DELETE /v1/files/file_1");
        KnowledgeSource s = row(id);
        assertThat(s.getState()).isEqualTo(SourceState.QUEUED);
        assertThat(s.getProviderFileId()).isNull();
        assertThat(gridText(s)).endsWith("second text");
        assertThat(s.getGridFsId()).isNotEqualTo(firstGrid);

        OPENAI.reset();
        OPENAI.enqueue(200, "{\"id\":\"file_2\"}");
        OPENAI.enqueue(200, "{}");
        tick();
        assertThat(row(id).getState()).isEqualTo(SourceState.INDEXING);
        assertThat(OPENAI.requests().get(0).body()).contains("second text");
    }
}
