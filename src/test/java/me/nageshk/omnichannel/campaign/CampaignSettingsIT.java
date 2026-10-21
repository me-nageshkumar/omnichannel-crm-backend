package me.nageshk.omnichannel.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PUT;

import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class CampaignSettingsIT extends CampaignTestBase {

    @Autowired CampaignSettingsRepository settingsRepo;
    @Autowired ObjectMapper mapper;

    private ObjectNode current() {
        return ((ObjectNode) api(GET, "/campaigns/settings", manager, null).getBody()).deepCopy();
    }

    @Test
    void firstReadCreatesTheDefaults() {
        JsonNode body = api(GET, "/campaigns/settings", manager, null).getBody();

        assertThat(body.get("timezone").asString()).isEqualTo("Asia/Kolkata");
        assertThat(body.get("startTime").asString()).isEqualTo("10:00");
        assertThat(body.get("workingDays")).hasSize(6);
        assertThat(body.at("/warmupCaps/4/cap").asInt()).isEqualTo(50);
        assertThat(body.get("stopWords").toString()).contains("don't message");
        assertThat(body.get("version").isNumber()).isTrue();
        assertThat(settingsRepo.count()).isEqualTo(1);
    }

    @Test
    void putStoresAndReturnsTheSettings() {
        ObjectNode change = current();
        change.put("hourlyCap", 20);
        change.set("stopWords", mapper.valueToTree(List.of(" Nahi ", "nahi", "STOP")));

        ResponseEntity<JsonNode> put = api(PUT, "/campaigns/settings", manager, change);

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody().get("hourlyCap").asInt()).isEqualTo(20);
        List<String> words = new ArrayList<>();
        put.getBody().get("stopWords").forEach(w -> words.add(w.asString()));
        assertThat(words).containsExactly("nahi", "stop");
        assertThat(put.getBody().get("version").asLong()).isGreaterThan(change.get("version").asLong());
        assertThat(api(GET, "/campaigns/settings", manager, null).getBody().get("hourlyCap").asInt()).isEqualTo(20);

        ResponseEntity<JsonNode> replay = api(PUT, "/campaigns/settings", manager, change);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(replay.getBody().get("code").asString()).isEqualTo("CONFLICT");
    }

    @Test
    void putRejectsBadValuesFieldByField() {
        ObjectNode bad = current();
        bad.put("timezone", "Mars/Base");
        bad.put("endTime", "09:00");
        bad.put("gapMaxSeconds", 10);
        bad.set("warmupCaps", mapper.valueToTree(List.of(java.util.Map.of("fromDay", 2, "cap", 5))));

        ResponseEntity<JsonNode> put = api(PUT, "/campaigns/settings", manager, bad);

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put.getBody().get("code").asString()).isEqualTo("VALIDATION");
        List<String> keys = new ArrayList<>();
        put.getBody().get("fields").propertyNames().forEach(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("timezone", "endTime", "gapMaxSeconds", "warmupCaps");
        assertThat(put.getBody().at("/fields/timezone").asString()).isEqualTo("is not a known time zone");
    }

    @Test
    void settingsNeedCampaignSettings() {
        assertThat(api(GET, "/campaigns/settings", lead, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api(PUT, "/campaigns/settings", agent, current()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void settingsAreScopedToTheWorkspace() {
        User otherManager = brandUser(other, "otherManager", BuiltInRoles.WORKSPACE_MANAGER);
        ObjectNode change = current();
        change.put("hourlyCap", 20);
        assertThat(api(PUT, "/campaigns/settings", manager, change).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> theirs = rest.exchange(
                url("/api/workspaces/" + other.getId() + "/campaigns/settings"), GET, auth(otherManager, null),
                JsonNode.class);

        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(theirs.getBody().get("hourlyCap").asInt()).isEqualTo(12);
    }
}
