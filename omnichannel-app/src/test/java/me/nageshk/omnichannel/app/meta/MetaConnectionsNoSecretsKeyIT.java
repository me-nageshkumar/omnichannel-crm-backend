package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** A server started without a secrets key refuses to save a key (its own context, hence its own class). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"omnichannel.rpc.timeout=2s", "omnichannel.secrets.keys="})
class MetaConnectionsNoSecretsKeyIT extends MetaTestBase {

    @Test
    void noSecretsKeyIs503() {
        ResponseEntity<JsonNode> res = meta(HttpMethod.POST, "/connections", manager,
                Map.of("name", "Bimal app", "appId", "111", "appSecret", APP_SECRET, "accessToken", TOKEN));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody().get("code").asString()).isEqualTo("secrets_unavailable");
        assertThat(meta(HttpMethod.GET, "/connections", manager, null).getBody().size()).isZero();
    }
}
