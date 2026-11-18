package me.nageshk.omnichannel.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import me.nageshk.omnichannel.ai.config.AiProperties;
import me.nageshk.omnichannel.ai.provider.AiCredential;
import me.nageshk.omnichannel.ai.provider.AiProviderException;
import me.nageshk.omnichannel.ai.provider.KnowledgeFileState;
import me.nageshk.omnichannel.ai.provider.KnowledgeFileState.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiKnowledgeTest {

    private static final String KEY = "sk-secret-123";
    private static final AiCredential CRED = new AiCredential(KEY);

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private OpenAiProvider provider(Consumer<HttpExchange> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().toString());
            method.set(ex.getRequestMethod());
            contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            try {
                body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                handler.accept(ex);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                ex.close();
            }
        });
        server.start();
        AiProperties props = new AiProperties(true, Duration.ofSeconds(2), 8, Duration.ofSeconds(5), Duration.ofSeconds(120),
                2, 8, Duration.ofSeconds(10),
                new AiProperties.OpenAi("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                new AiProperties.Mcp(Duration.ofSeconds(20), 65536, Duration.ofMinutes(5)),
                AiProperties.Knowledge.defaults());
        return new OpenAiProvider(props);
    }

    private static void reply(HttpExchange ex, int status, String json) {
        try {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createStorePostsNameAndReturnsId() throws IOException {
        OpenAiProvider p = provider(ex -> reply(ex, 200, "{\"id\":\"vs_1\"}"));
        assertThat(p.createStore(CRED, "Bimal courses")).isEqualTo("vs_1");
        assertThat(path.get()).isEqualTo("/v1/vector_stores");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(body.get()).isEqualTo("{\"name\":\"Bimal courses\"}");
    }

    @Test
    void uploadFileSendsMultipartWithPurposeAssistants() throws IOException {
        OpenAiProvider p = provider(ex -> reply(ex, 200, "{\"id\":\"file_1\"}"));
        String id = p.uploadFile(CRED, "CFTP.pdf", "application/pdf", "PDFBYTES".getBytes(StandardCharsets.UTF_8));
        assertThat(id).isEqualTo("file_1");
        assertThat(path.get()).isEqualTo("/v1/files");
        assertThat(contentType.get()).startsWith("multipart/form-data; boundary=");
        assertThat(body.get()).contains("name=\"purpose\"", "assistants", "filename=\"CFTP.pdf\"",
                "Content-Type: application/pdf", "PDFBYTES");
    }

    @Test
    void attachPostsFileId() throws IOException {
        OpenAiProvider p = provider(ex -> reply(ex, 200, "{\"id\":\"file_1\"}"));
        p.attach(CRED, "vs_1", "file_1");
        assertThat(path.get()).isEqualTo("/v1/vector_stores/vs_1/files");
        assertThat(body.get()).isEqualTo("{\"file_id\":\"file_1\"}");
    }

    @Test
    void fileStateMapsStatuses() throws IOException {
        AtomicReference<String> reply = new AtomicReference<>();
        OpenAiProvider p = provider(ex -> reply(ex, 200, reply.get()));
        reply.set("{\"status\":\"in_progress\"}");
        assertThat(p.fileState(CRED, "vs_1", "file_1")).isEqualTo(new KnowledgeFileState(Kind.INDEXING, null));
        assertThat(path.get()).isEqualTo("/v1/vector_stores/vs_1/files/file_1");
        reply.set("{\"status\":\"completed\"}");
        assertThat(p.fileState(CRED, "vs_1", "file_1")).isEqualTo(new KnowledgeFileState(Kind.READY, null));
        reply.set("{\"status\":\"failed\",\"last_error\":{\"code\":\"unsupported_file\","
                + "\"message\":\"File type not supported\"}}");
        assertThat(p.fileState(CRED, "vs_1", "file_1"))
                .isEqualTo(new KnowledgeFileState(Kind.FAILED, "File type not supported"));
        reply.set("{\"status\":\"cancelled\"}");
        assertThat(p.fileState(CRED, "vs_1", "file_1")).isEqualTo(new KnowledgeFileState(Kind.FAILED, "Cancelled"));
    }

    @Test
    void deleteMissingStoreCountsAsDone() throws IOException {
        OpenAiProvider p = provider(ex -> reply(ex, 404, "{\"error\":{\"message\":\"No such thing\"}}"));
        p.deleteStore(CRED, "vs_gone");
        assertThat(method.get()).isEqualTo("DELETE");
        assertThat(path.get()).isEqualTo("/v1/vector_stores/vs_gone");
        p.detach(CRED, "vs_gone", "file_1");
        assertThat(path.get()).isEqualTo("/v1/vector_stores/vs_gone/files/file_1");
        p.deleteFile(CRED, "file_1");
        assertThat(path.get()).isEqualTo("/v1/files/file_1");
    }

    @Test
    void knowledgeErrorsRedactTheKey() throws IOException {
        OpenAiProvider p = provider(ex -> reply(ex, 400, "{\"error\":{\"message\":\"bad key " + KEY + " here\"}}"));
        assertThatThrownBy(() -> p.createStore(CRED, "x")).isInstanceOf(AiProviderException.class)
                .hasMessageContaining("«key»").hasMessageNotContaining(KEY);
        assertThatThrownBy(() -> p.uploadFile(CRED, "a.txt", "text/plain", new byte[] {1}))
                .isInstanceOf(AiProviderException.class).hasMessageContaining("«key»");
    }
}
