package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.service.cloud.CloudMediaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/** Spec §8.3: the editor's own media upload and preview; the media sweeper keeps files a flow references. */
class ChatbotMediaIT extends ChatbotTestBase {

    @Autowired CloudMediaService media;

    static byte[] png(int size) {
        byte[] b = new byte[size];
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, b, 0, sig.length);
        return b;
    }

    private ResponseEntity<JsonNode> upload(String chatbotId, String type, String name, byte[] bytes) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(testUsers.accessToken(manager));
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        body.add("type", type);
        return rest.exchange(url("/api/workspaces/" + brand.getId() + "/chatbots/" + chatbotId + "/media"), HttpMethod.POST,
                new HttpEntity<>(body, h), JsonNode.class);
    }

    private ResponseEntity<String> preview(String chatbotId, Map<String, Object> message) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(testUsers.accessToken(manager));
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url("/api/workspaces/" + brand.getId() + "/chatbots/" + chatbotId + "/preview"), HttpMethod.POST,
                new HttpEntity<>(message, h), String.class);
    }

    @Test
    void uploadThroughChatbotEndpoint() {
        JsonNode bot = createChatbot("Bot", flow(List.of(), List.of()));
        ResponseEntity<JsonNode> r = upload(bot.get("id").asString(), "image", "a.png", png(100));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String id = r.getBody().get("id").asString();
        assertThat(media.mimeOf(brand.getId(), id)).contains("image/png");
        assertThat(upload(bot.get("id").asString(), "video", "a.png", png(100)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(testUsers.accessToken(agent));
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("type", "image");
        assertThat(rest.exchange(url("/api/workspaces/" + brand.getId() + "/chatbots/" + bot.get("id").asString() + "/media"),
                HttpMethod.POST, new HttpEntity<>(body, h), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void previewRendersButtons() {
        JsonNode bot = createChatbot("Bot", flow(List.of(), List.of()));
        Map<String, Object> message = Map.of("type", "interactive", "interactive", Map.of("type", "button",
                "body", Map.of("text", "Book a *demo*?"), "action", Map.of("buttons", List.of(
                        Map.of("type", "reply", "reply", Map.of("id", "yes", "title", "Yes please")),
                        Map.of("type", "reply", "reply", Map.of("id", "no", "title", "Not now"))))));
        ResponseEntity<String> r = preview(bot.get("id").asString(), message);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(r.getBody()).contains("Yes please").contains("Not now").contains("<strong>demo</strong>").doesNotContain("/t/preview/e");
    }

    @Test
    void previewRendersTextImageAndLocation() {
        JsonNode bot = createChatbot("Bot", flow(List.of(), List.of()));
        String id = bot.get("id").asString();
        ResponseEntity<String> text = preview(id, Map.of("type", "text", "text", Map.of("body", "Hi _there_")));
        assertThat(text.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(text.getBody()).contains("Hi <em>there</em>");
        String mediaId = upload(id, "image", "a.png", png(100)).getBody().get("id").asString();
        ResponseEntity<String> image = preview(id, Map.of("type", "image", "image", Map.of("id", mediaId, "caption", "Our shop")));
        assertThat(image.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(image.getBody()).contains("Our shop").contains("data:image/png;base64,");
        ResponseEntity<String> location = preview(id, Map.of("type", "location", "location",
                Map.of("latitude", 12.9716, "longitude", 77.5946, "name", "Our store", "address", "MG Road")));
        assertThat(location.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(location.getBody()).contains("Our store").contains("MG Road");
        ResponseEntity<String> bad = preview(id, Map.of("type", "image", "image", Map.of("id", "nope")));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sweeperKeepsMediaUsedByAFlowOrAnOldVersion() {
        JsonNode bot = createChatbot("Bot", flow(List.of(), List.of()));
        String id = bot.get("id").asString();
        String used = upload(id, "image", "a.png", png(100)).getBody().get("id").asString();
        String old = upload(id, "image", "b.png", png(100)).getBody().get("id").asString();
        String orphan = upload(id, "image", "c.png", png(100)).getBody().get("id").asString();
        Map<String, Object> withOld = flow(List.of(node("n1", "send_message", "Img",
                Map.of("message", Map.of("type", "image", "image", Map.of("id", old))))), List.of());
        JsonNode v1 = bots(HttpMethod.PUT, "/" + id, manager, Map.of("flow", withOld, "version", bot.get("version").asLong()))
                .getBody().get("chatbot");
        Map<String, Object> withUsed = flow(List.of(node("n1", "send_message", "Img",
                Map.of("message", Map.of("type", "image", "image", Map.of("id", used))))), List.of());
        bots(HttpMethod.PUT, "/" + id, manager, Map.of("flow", withUsed, "version", v1.get("version").asLong()));
        clock.advance(Duration.ofDays(2));
        int deleted = media.sweepOrphans(clock.instant().minus(Duration.ofHours(24)));
        assertThat(deleted).isEqualTo(1);
        assertThat(media.mimeOf(brand.getId(), used)).isPresent();
        assertThat(media.mimeOf(brand.getId(), old)).isPresent();
        assertThat(media.mimeOf(brand.getId(), orphan)).isEmpty();
    }
}
