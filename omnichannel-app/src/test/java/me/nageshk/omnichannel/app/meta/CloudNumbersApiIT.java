package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class CloudNumbersApiIT extends MetaTestBase {

    @org.springframework.beans.factory.annotation.Autowired me.nageshk.omnichannel.app.support.TestBilling billing;

    @org.junit.jupiter.api.AfterEach
    void resetBilling() {
        billing.reset();
    }

    private long cloudNumbers() {
        return phoneNumbers.findAll().stream()
                .filter(n -> n.getConnectionType() == me.nageshk.omnichannel.db.phone.ConnectionType.WHATSAPP_CLOUD_API)
                .count();
    }

    private ResponseEntity<JsonNode> add(String connectionId, String metaId) {
        return numbers(HttpMethod.POST, "", manager, Map.of("label", "Admissions", "connectionType",
                "WHATSAPP_CLOUD_API", "cloud", Map.of("connectionId", connectionId, "wabaId", "222",
                        "metaPhoneNumberId", metaId)));
    }

    @Test
    void addsCloudNumberWithMetaDetails() {
        String conn = connect("111").get("id").asString();
        GRAPH.reset();
        stubNumber("333", "+91 98765 43210", true);

        ResponseEntity<JsonNode> res = add(conn, "333");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode v = res.getBody();
        assertThat(v.get("number").asString()).isEqualTo("919876543210");
        assertThat(v.get("connectionType").asString()).isEqualTo("WHATSAPP_CLOUD_API");
        JsonNode cloud = v.get("cloud");
        assertThat(cloud.get("verifiedName").asString()).isEqualTo("Bimal Institute");
        assertThat(cloud.get("metaPhoneNumberId").asString()).isEqualTo("333");
        assertThat(cloud.get("wabaId").asString()).isEqualTo("222");
        assertThat(cloud.get("connectionName").asString()).isEqualTo("Bimal app");
        assertThat(cloud.get("qualityRating").asString()).isEqualTo("GREEN");
        assertThat(cloud.get("messagingLimitTier").asString()).isEqualTo("TIER_250");
        assertThat(cloud.get("registered").asBoolean()).isTrue();
        assertThat(cloud.get("state").asString()).isEqualTo("WAITING_FOR_WEBHOOK");
        assertThat(GRAPH.recorded("POST /222/subscribed_apps")).hasSize(1);
        // listed with the brand's numbers, and the connection knows its number
        JsonNode listed = null;
        for (JsonNode n : numbers(HttpMethod.GET, "", manager, null).getBody()) {
            if ("WHATSAPP_CLOUD_API".equals(n.get("connectionType").asString())) listed = n;
        }
        assertThat(listed.get("cloud").get("metaPhoneNumberId").asString()).isEqualTo("333");
        JsonNode connection = meta(HttpMethod.GET, "/connections/" + conn, manager, null).getBody();
        assertThat(connection.get("numbers").get(0).get("label").asString()).isEqualTo("Admissions");
    }

    @Test
    void numberNotInWabaIs400() {
        String conn = connect("111").get("id").asString();
        GRAPH.reset();
        GRAPH.enqueue("GET /333", 200, "{\"id\":\"333\",\"display_phone_number\":\"+91 98765 43210\"}");
        GRAPH.enqueue("GET /222/phone_numbers", 200, "{\"data\":[{\"id\":\"444\"}]}");

        ResponseEntity<JsonNode> res = add(conn, "333");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fields").get("cloud").asString()).contains("222");
        assertThat(cloudNumbers()).isZero();
    }

    @Test
    void metaErrorIs400WithMetaMessage() {
        String conn = connect("111").get("id").asString();
        GRAPH.reset();
        GRAPH.enqueue("GET /333", 400, "{\"error\":{\"message\":\"Unsupported get request\",\"code\":100}}");

        ResponseEntity<JsonNode> res = add(conn, "333");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fields").get("cloud").asString()).contains("Unsupported get request");
        assertThat(cloudNumbers()).isZero();
    }

    @Test
    void cloudDetailsAreRequired() {
        ResponseEntity<JsonNode> res = numbers(HttpMethod.POST, "", manager,
                Map.of("label", "Admissions", "connectionType", "WHATSAPP_CLOUD_API"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fields").has("cloud")).isTrue();
    }

    @Test
    void duplicateNumberAcrossBrandsIs409() {
        String conn = connect("111").get("id").asString();
        PhoneNumber existing = new PhoneNumber();
        existing.setNumber("919876543210");
        existing.setCustomerId(acme.getId());
        existing.setWorkspaceId(other.getId());
        phoneNumbers.save(existing);
        stubNumber("333", "+91 98765 43210", true);

        assertThat(add(conn, "333").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mongo.getCollection("cloudAccounts").countDocuments()).isZero();
    }

    @Test
    void sameMetaNumberTwiceIs409() {
        String conn = connect("111").get("id").asString();
        addCloudNumber(conn, "Admissions", "333", "+91 98765 43210");
        stubNumber("333", "+91 98765 43210", true);

        assertThat(add(conn, "333").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void notRegisteredNumberShowsNotRegistered() {
        String conn = connect("111").get("id").asString();
        stubNumber("333", "+91 98765 43210", false);

        JsonNode cloud = add(conn, "333").getBody().get("cloud");

        assertThat(cloud.get("registered").asBoolean()).isFalse();
        assertThat(cloud.get("state").asString()).isEqualTo("NOT_REGISTERED");
    }

    @Test
    void registerSendsPinAndFlipsState() {
        String conn = connect("111").get("id").asString();
        stubNumber("333", "+91 98765 43210", false);
        String id = add(conn, "333").getBody().get("id").asString();
        GRAPH.reset();
        GRAPH.enqueue("POST /333/register", 200, "{\"success\":true}");
        GRAPH.enqueue("GET /333", 200, "{\"id\":\"333\",\"display_phone_number\":\"+91 98765 43210\","
                + "\"status\":\"CONNECTED\",\"host_platform\":\"CLOUD_API\"}");

        ResponseEntity<JsonNode> res = numbers(HttpMethod.POST, "/" + id + "/cloud/register", manager,
                Map.of("pin", "123456"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("cloud").get("registered").asBoolean()).isTrue();
        JsonNode sent = mapper.readTree(GRAPH.recorded("POST /333/register").get(0).body());
        assertThat(sent.get("messaging_product").asString()).isEqualTo("whatsapp");
        assertThat(sent.get("pin").asString()).isEqualTo("123456");
        for (String c : new String[] {"cloudAccounts", "phoneNumbers", "metaConnections"}) {
            for (Document d : mongo.getCollection(c).find()) assertThat(d.toJson()).doesNotContain("123456");
        }
    }

    @Test
    void registerNeedsSixDigits() {
        String conn = connect("111").get("id").asString();
        stubNumber("333", "+91 98765 43210", false);
        String id = add(conn, "333").getBody().get("id").asString();

        assertThat(numbers(HttpMethod.POST, "/" + id + "/cloud/register", manager, Map.of("pin", "12a")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deviceActionsAreRefused() {
        String conn = connect("111").get("id").asString();
        String id = addCloudNumber(conn, "Admissions", "333", "+91 98765 43210").get("id").asString();

        for (String action : new String[] {"/connect-code", "/revoke-device"}) {
            ResponseEntity<JsonNode> res = numbers(HttpMethod.POST, "/" + id + action, manager, null);
            assertThat(res.getStatusCode()).as(action).isEqualTo(HttpStatus.CONFLICT);
            assertThat(res.getBody().get("code").asString()).isEqualTo("CLOUD_NUMBER");
        }
        ResponseEntity<JsonNode> move = rest.exchange(url("/api/phone-numbers/" + id + "/move/preview?toWorkspaceId="
                + other.getId()), HttpMethod.GET, auth(staff, null), JsonNode.class);
        assertThat(move.getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void deleteRemovesAccount() {
        String conn = connect("111").get("id").asString();
        String id = addCloudNumber(conn, "Admissions", "333", "+91 98765 43210").get("id").asString();

        assertThat(numbers(HttpMethod.DELETE, "/" + id, manager, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mongo.getCollection("cloudAccounts").countDocuments()).isZero();
    }

    @Test
    void connectionWithNumbersCannotBeDeleted() {
        String conn = connect("111").get("id").asString();
        addCloudNumber(conn, "Admissions", "333", "+91 98765 43210");

        ResponseEntity<JsonNode> res = meta(HttpMethod.DELETE, "/connections/" + conn, manager, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("field").asString()).isEqualTo("numbers");
        assertThat(res.getBody().get("message").asString()).contains("Admissions");
    }

    @Test
    void checkMarksNumbersAlreadyAdded() {
        String conn = connect("111").get("id").asString();
        String id = addCloudNumber(conn, "Admissions", "333", "+91 98765 43210").get("id").asString();
        GRAPH.enqueue("GET /debug_token", 200, me.nageshk.omnichannel.app.support.GraphStub.validToken("111", "222"));
        GRAPH.enqueue("GET /222/phone_numbers", 200, "{\"data\":[{\"id\":\"333\"},{\"id\":\"444\"}]}");

        JsonNode numbersOfWaba = meta(HttpMethod.POST, "/connections/" + conn + "/check", manager, null).getBody()
                .get("accounts").get(0).get("numbers");

        assertThat(numbersOfWaba.get(0).get("phoneNumberId").asString()).isEqualTo(id);
        assertThat(numbersOfWaba.get(1).get("phoneNumberId").isNull()).isTrue();
    }

    @Test
    void planLimitStillApplies() {
        String conn = connect("111").get("id").asString();
        billing.assign(acme.getId(), java.util.Set.of(),
                Map.of(me.nageshk.omnichannel.common.billing.LimitKey.PHONE_NUMBERS, 0));
        stubNumber("333", "+91 98765 43210", true);

        ResponseEntity<JsonNode> res = add(conn, "333");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("key").asString()).isEqualTo("PHONE_NUMBERS");
        assertThat(mongo.getCollection("cloudAccounts").countDocuments()).isZero();
    }
}
