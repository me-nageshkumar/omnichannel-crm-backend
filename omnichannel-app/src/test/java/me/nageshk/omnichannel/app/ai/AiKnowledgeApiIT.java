package me.nageshk.omnichannel.app.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeBase;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeService;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeSource;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.RoleCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

class AiKnowledgeApiIT extends AiTestBase {

    private static final String KEY = "sk-test-1234567890k77Z";

    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;
    @Autowired @Qualifier("aiKnowledgeFilesGridFs") GridFsTemplate files;
    @Autowired KnowledgeService knowledge;

    private final List<String> bodies = new ArrayList<>();

    @Override
    protected List<Class<?>> collections() {
        List<Class<?>> c = new ArrayList<>(super.collections());
        c.add(KnowledgeBase.class);
        c.add(KnowledgeSource.class);
        return c;
    }

    @BeforeEach
    void clearFiles() {
        files.delete(new Query());
        bodies.clear();
    }

    private ResponseEntity<JsonNode> send(HttpMethod m, String path, User who, Object body) {
        ResponseEntity<JsonNode> res = ai(m, path, who, body);
        bodies.add(String.valueOf(res.getBody()));
        return res;
    }

    private String connection(String name) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("provider", "openai");
        b.put("apiKey", KEY);
        return ai(HttpMethod.POST, "/connections", manager, b).getBody().get("id").asString();
    }

    private ResponseEntity<JsonNode> createKb(String name, String connectionId, String storeId) {
        OPENAI.enqueue(200, "{\"id\":\"" + storeId + "\"}");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("description", "About " + name);
        b.put("connectionId", connectionId);
        return send(HttpMethod.POST, "/knowledge-bases", manager, b);
    }

    private String kb(String name) {
        return createKb(name, connection("Conn " + name), "vs_" + name.hashCode()).getBody().get("id").asString();
    }

    private ResponseEntity<JsonNode> note(String kbId, String title, String text) {
        return send(HttpMethod.POST, "/knowledge-bases/" + kbId + "/sources/note", manager,
                Map.of("title", title, "text", text));
    }

    private ResponseEntity<JsonNode> upload(String kbId, String fileName, byte[] bytes, User who) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(testUsers.accessToken(who));
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        ResponseEntity<JsonNode> res = rest.exchange(
                url("/api/workspaces/" + brand.getId() + "/ai/knowledge-bases/" + kbId + "/sources/file"),
                HttpMethod.POST, new HttpEntity<>(body, h), JsonNode.class);
        bodies.add(String.valueOf(res.getBody()));
        return res;
    }

    private User roleUser(String code, String prefix) {
        Role r = new Role();
        r.setName(prefix + COUNTER.incrementAndGet());
        r.setLevel(RoleLevel.BRAND);
        r.setAuthorities(Set.of(code));
        r = roles.save(r);
        roleCache.evictAll();
        return testUsers.createInCustomer(acme.getId(), prefix.toLowerCase() + COUNTER.incrementAndGet(), null,
                List.of(new WorkspaceRole(brand.getId(), r.getId())));
    }

    private void profileUsing(String name, String storeId) {
        mongo.insert(new org.bson.Document("workspaceId", brand.getId()).append("name", name)
                .append("vectorStoreIds", List.of(storeId)), "aiProfiles");
    }

    private String gridText(String sourceId) throws IOException {
        KnowledgeSource s = mongo.findById(sourceId, KnowledgeSource.class);
        var file = files.findOne(new Query(Criteria.where("_id").is(s.getGridFsId())));
        return new String(files.getResource(file).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void createKnowledgeBaseCreatesTheStoreAtOpenAi() {
        String conn = connection("Main");

        ResponseEntity<JsonNode> res = createKb("Courses", conn, "vs_1");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode v = res.getBody();
        assertThat(v.get("providerStoreId").asString()).isEqualTo("vs_1");
        assertThat(v.get("status").asString()).isEqualTo("READY");
        assertThat(v.get("provider").asString()).isEqualTo("openai");
        assertThat(v.get("connectionName").asString()).isEqualTo("Main");
        for (String k : List.of("queued", "adding", "ready", "failed")) {
            assertThat(v.get("counts").get(k).asInt()).isZero();
        }
        assertThat(v.get("sourceCount").asInt()).isZero();
        var req = OPENAI.last();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/v1/vector_stores");
        assertThat(req.body()).contains("Courses");
    }

    @Test
    void createRefusedWhenOpenAiRefuses() {
        String conn = connection("Main");
        OPENAI.enqueue(401, "{\"error\":{\"message\":\"bad key\"}}");

        ResponseEntity<JsonNode> res = send(HttpMethod.POST, "/knowledge-bases", manager,
                Map.of("name", "Courses", "description", "", "connectionId", conn));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(res.getBody().get("code").asString()).isEqualTo("ai_provider_refused_key");
        assertThat(mongo.findAll(KnowledgeBase.class)).isEmpty();
    }

    @Test
    void createNeedsAStoreCapableProvider() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", "Fake");
        c.put("provider", "scripted");
        c.put("apiKey", "good");
        String conn = ai(HttpMethod.POST, "/connections", manager, c).getBody().get("id").asString();

        ResponseEntity<JsonNode> res = send(HttpMethod.POST, "/knowledge-bases", manager,
                Map.of("name", "Courses", "description", "", "connectionId", conn));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asString()).isEqualTo("ai_knowledge_no_store_support");
    }

    @Test
    void addNoteQueuesASource() throws IOException {
        String id = kb("Courses");

        ResponseEntity<JsonNode> res = note(id, "Counsellor pitch", "Say hello — ₹9,999");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode v = res.getBody();
        assertThat(v.get("state").asString()).isEqualTo("QUEUED");
        assertThat(v.get("kind").asString()).isEqualTo("NOTE");
        assertThat(v.get("title").asString()).isEqualTo("Counsellor pitch");
        String md = "# Counsellor pitch\n\nSay hello — ₹9,999";
        assertThat(v.get("size").asInt()).isEqualTo(md.getBytes(StandardCharsets.UTF_8).length);
        assertThat(gridText(v.get("id").asString())).isEqualTo(md);
        ResponseEntity<byte[]> content = rest.exchange(url("/api/workspaces/" + brand.getId() + "/ai/knowledge-bases/"
                + id + "/sources/" + v.get("id").asString() + "/content"), HttpMethod.GET, auth(manager, null),
                byte[].class);
        assertThat(content.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(content.getBody(), StandardCharsets.UTF_8)).isEqualTo(md);
    }

    @Test
    void noteNeedsTitleAndText() {
        String id = kb("Courses");

        ResponseEntity<JsonNode> res = note(id, " ", "");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().toString()).contains("title").contains("text");
    }

    @Test
    void addFileChecksType() {
        String id = kb("Courses");

        ResponseEntity<JsonNode> pdf = upload(id, "brochure.pdf", "%PDF-1.4 x".getBytes(StandardCharsets.UTF_8), manager);
        ResponseEntity<JsonNode> exe = upload(id, "virus.exe", new byte[] {1, 2, 3}, manager);

        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(pdf.getBody().get("state").asString()).isEqualTo("QUEUED");
        assertThat(pdf.getBody().get("kind").asString()).isEqualTo("FILE");
        assertThat(pdf.getBody().get("fileName").asString()).isEqualTo("brochure.pdf");
        assertThat(pdf.getBody().get("contentType").asString()).isEqualTo("application/pdf");
        assertThat(pdf.getBody().get("size").asInt()).isEqualTo(10);
        assertThat(exe.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exe.getBody().get("code").asString()).isEqualTo("ai_knowledge_file_type");
        assertThat(exe.getBody().get("message").asString()).isEqualTo("PDF, DOCX, PPTX, TXT, MD or HTML only");
    }

    @Test
    void addWebPageNeedsHttps() {
        String id = kb("Courses");

        ResponseEntity<JsonNode> http = send(HttpMethod.POST, "/knowledge-bases/" + id + "/sources/web-page", manager,
                Map.of("url", "http://bimalinstitute.com"));
        ResponseEntity<JsonNode> https = send(HttpMethod.POST, "/knowledge-bases/" + id + "/sources/web-page", manager,
                Map.of("url", "https://bimalinstitute.com/"));

        assertThat(http.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getBody().toString()).contains("url");
        assertThat(https.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(https.getBody().get("state").asString()).isEqualTo("QUEUED");
        assertThat(https.getBody().get("kind").asString()).isEqualTo("WEB_PAGE");
        assertThat(https.getBody().get("title").asString()).isEqualTo("https://bimalinstitute.com/");
        assertThat(https.getBody().get("url").asString()).isEqualTo("https://bimalinstitute.com/");
    }

    @Test
    void deleteKnowledgeBaseUsedByProfile409() {
        String id = createKb("Courses", connection("Main"), "vs_1").getBody().get("id").asString();
        profileUsing("Counsellor", "vs_1");

        ResponseEntity<JsonNode> res = send(HttpMethod.DELETE, "/knowledge-bases/" + id, manager, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("ai_knowledge_in_use");
        assertThat(res.getBody().get("message").asString()).isEqualTo("Used by profiles: Counsellor");
        assertThat(mongo.findAll(KnowledgeBase.class)).hasSize(1);
    }

    @Test
    void deleteKnowledgeBaseWhoseStoreIsGone() {
        String id = createKb("Courses", connection("Main"), "vs_1").getBody().get("id").asString();
        note(id, "A", "text");
        note(id, "B", "text");
        OPENAI.enqueue(404, "{\"error\":{\"message\":\"No such vector store\"}}");

        ResponseEntity<JsonNode> res = send(HttpMethod.DELETE, "/knowledge-bases/" + id, manager, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mongo.findAll(KnowledgeBase.class)).isEmpty();
        assertThat(mongo.findAll(KnowledgeSource.class)).isEmpty();
        assertThat(files.find(new Query()).iterator().hasNext()).isFalse();
    }

    @Test
    void deleteConnectionHoldingKnowledgeBase409() {
        String conn = connection("Main");
        createKb("Courses", conn, "vs_1");

        ResponseEntity<JsonNode> res = send(HttpMethod.DELETE, "/connections/" + conn, manager, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("ai_connection_in_use");
        assertThat(res.getBody().get("message").asString()).isEqualTo("Used by knowledge bases: Courses");
    }

    @Test
    void listShowsCountsAndUsedBy() {
        String id = createKb("Courses", connection("Main"), "vs_1").getBody().get("id").asString();
        profileUsing("Counsellor", "vs_1");
        for (int i = 0; i < 4; i++) note(id, "N" + i, "t");
        List<KnowledgeSource> rows = new ArrayList<>(mongo.findAll(KnowledgeSource.class));
        AtomicInteger i = new AtomicInteger();
        for (var state : List.of("READY", "READY", "FAILED", "INDEXING")) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(rows.get(i.getAndIncrement()).getId())),
                    new org.springframework.data.mongodb.core.query.Update().set("state", state), KnowledgeSource.class);
        }

        JsonNode v = send(HttpMethod.GET, "/knowledge-bases", manager, null).getBody().get(0);

        assertThat(v.get("counts").get("queued").asInt()).isZero();
        assertThat(v.get("counts").get("adding").asInt()).isEqualTo(1);
        assertThat(v.get("counts").get("ready").asInt()).isEqualTo(2);
        assertThat(v.get("counts").get("failed").asInt()).isEqualTo(1);
        assertThat(v.get("sourceCount").asInt()).isEqualTo(4);
        assertThat(v.get("usedBy")).hasSize(1);
        assertThat(v.get("usedBy").get(0).asString()).isEqualTo("Counsellor");
        assertThat(send(HttpMethod.GET, "/knowledge-bases/" + id + "/sources", manager, null).getBody()).hasSize(4);
    }

    @Test
    void viewerCannotChange() {
        String id = kb("Courses");
        User viewer = roleUser("ai:view", "Viewer");

        assertThat(send(HttpMethod.GET, "/knowledge-bases", viewer, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send(HttpMethod.POST, "/knowledge-bases/" + id + "/sources/note", viewer,
                Map.of("title", "t", "text", "x")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(id, "a.txt", new byte[] {65}, viewer).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void limitsEnforced() {
        String conn = connection("Main");
        for (int i = 0; i < 20; i++) {
            mongo.insert(new org.bson.Document("workspaceId", brand.getId()).append("name", "KB" + i)
                    .append("connectionId", conn).append("providerStoreId", "vs_x" + i), "aiKnowledgeBases");
        }
        ResponseEntity<JsonNode> kb21 = createKb("One too many", conn, "vs_21");
        assertThat(kb21.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(kb21.getBody().get("code").asString()).isEqualTo("ai_knowledge_limit");
        assertThat(kb21.getBody().get("message").asString()).isEqualTo("At most 20 knowledge bases");

        String id = mongo.findAll(KnowledgeBase.class).get(0).getId();
        for (int i = 0; i < 200; i++) {
            mongo.insert(new org.bson.Document("workspaceId", brand.getId()).append("knowledgeBaseId", id)
                    .append("kind", "NOTE").append("title", "n" + i).append("state", "READY"), "aiKnowledgeSources");
        }
        ResponseEntity<JsonNode> s201 = note(id, "Too many", "x");
        assertThat(s201.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(s201.getBody().get("code").asString()).isEqualTo("ai_knowledge_limit");
        assertThat(s201.getBody().get("message").asString()).isEqualTo("At most 200 sources");
    }

    @Test
    void updateKnowledgeBaseNameAndDescription() {
        JsonNode v = createKb("Courses", connection("Main"), "vs_1").getBody();

        ResponseEntity<JsonNode> res = send(HttpMethod.PUT, "/knowledge-bases/" + v.get("id").asString(), manager,
                Map.of("name", "Programs", "description", "New", "version", v.get("version").asLong()));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("name").asString()).isEqualTo("Programs");
        assertThat(res.getBody().get("providerStoreId").asString()).isEqualTo("vs_1");
    }

    @Test
    void removingASourceMarksItRemovingAndWakesTheWorker() {
        String id = kb("Courses");
        String sid = note(id, "A", "x").getBody().get("id").asString();
        AtomicInteger woken = new AtomicInteger();
        knowledge.registerWaker(woken::incrementAndGet);

        ResponseEntity<JsonNode> res = send(HttpMethod.DELETE, "/knowledge-bases/" + id + "/sources/" + sid, manager, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(res.getBody().get("state").asString()).isEqualTo("REMOVING");
        assertThat(woken.get()).isEqualTo(1);
        knowledge.registerWaker(() -> {});
    }

    @Test
    void editingANoteRequeuesItAndKeepsTheProviderFileForTheWorker() throws IOException {
        String id = createKb("Courses", connection("Main"), "vs_1").getBody().get("id").asString();
        String sid = note(id, "A", "old").getBody().get("id").asString();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(sid)), new org.springframework.data.mongodb.core.query.Update()
                .set("state", "READY").set("providerFileId", "file_1").set("attempts", 2), KnowledgeSource.class);
        OPENAI.reset();
        AtomicInteger woken = new AtomicInteger();
        knowledge.registerWaker(woken::incrementAndGet);

        ResponseEntity<JsonNode> res = send(HttpMethod.PUT, "/knowledge-bases/" + id + "/sources/" + sid, manager,
                Map.of("text", "new text"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("state").asString()).isEqualTo("QUEUED");
        assertThat(gridText(sid)).isEqualTo("# A\n\nnew text");
        KnowledgeSource s = mongo.findById(sid, KnowledgeSource.class);
        assertThat(s.getProviderFileId()).isNull();
        assertThat(s.getReplacedFileIds()).containsExactly("file_1");
        assertThat(s.getAttempts()).isZero();
        // the old file keeps answering until the worker attached the new one
        assertThat(OPENAI.requests()).isEmpty();
        assertThat(woken.get()).isEqualTo(1);
        assertThat(files.find(new Query()).into(new ArrayList<>())).hasSize(1);
        knowledge.registerWaker(() -> {});
    }

    @Test
    void retryAndFetchAgainRules() {
        String id = createKb("Courses", connection("Main"), "vs_1").getBody().get("id").asString();
        String sid = note(id, "A", "x").getBody().get("id").asString();
        String path = "/knowledge-bases/" + id + "/sources/" + sid;

        assertThat(send(HttpMethod.PUT, path, manager, Map.of("action", "RETRY")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(HttpMethod.PUT, path, manager, Map.of("action", "FETCH_AGAIN")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(sid)), new org.springframework.data.mongodb.core.query.Update()
                .set("state", "FAILED").set("error", "boom"), KnowledgeSource.class);
        ResponseEntity<JsonNode> retry = send(HttpMethod.PUT, path, manager, Map.of("action", "RETRY"));
        assertThat(retry.getBody().get("state").asString()).isEqualTo("QUEUED");
        assertThat(retry.getBody().get("error").isNull()).isTrue();
    }

    @Test
    void noKeyInResponses() {
        String id = kb("Courses");
        note(id, "A", "x");
        send(HttpMethod.GET, "/knowledge-bases", manager, null);
        send(HttpMethod.GET, "/knowledge-bases/" + id, manager, null);
        send(HttpMethod.GET, "/knowledge-bases/" + id + "/sources", manager, null);

        assertThat(bodies).isNotEmpty().allSatisfy(b -> assertThat(b).doesNotContain(KEY));
    }
}
