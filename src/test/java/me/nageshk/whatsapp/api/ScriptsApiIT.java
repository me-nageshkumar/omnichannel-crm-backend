package me.nageshk.whatsapp.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import me.nageshk.whatsapp.auth.JwtService;
import me.nageshk.whatsapp.scripts.ScriptCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScriptsApiIT {

    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired ScriptCatalog catalog;

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issue("demo").token());
        return h;
    }

    @Test
    void manifestListsPageScript() {
        ResponseEntity<JsonNode> res = rest.exchange("/api/scripts", HttpMethod.GET, new HttpEntity<>(auth()), JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("page").get("sha256").asText()).isEqualTo(catalog.find("page").orElseThrow().sha256());
    }

    @Test
    void scriptIsServedAsJavascriptWithEtagAnd304() {
        String sha = catalog.find("page").orElseThrow().sha256();
        ResponseEntity<byte[]> res = rest.exchange("/api/scripts/page", HttpMethod.GET, new HttpEntity<>(auth()), byte[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType().toString()).startsWith("application/javascript");
        assertThat(res.getHeaders().getETag()).isEqualTo("\"" + sha + "\"");
        assertThat(ScriptCatalog.sha256Hex(res.getBody())).isEqualTo(sha);

        HttpHeaders h = auth();
        h.setIfNoneMatch("\"" + sha + "\"");
        ResponseEntity<byte[]> cached = rest.exchange("/api/scripts/page", HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        assertThat(cached.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void unknownScriptIs404AndNoTokenIs401() {
        ResponseEntity<JsonNode> missing = rest.exchange("/api/scripts/nope", HttpMethod.GET, new HttpEntity<>(auth()), JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> anon = rest.getForEntity("/api/scripts/page", String.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
