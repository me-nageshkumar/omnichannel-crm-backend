package me.nageshk.omnichannel.app.contact;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mongodb.client.MongoClient;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.app.support.MongoTestCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** A filter query over its time limit answers 504 QUERY_TIMEOUT; the failpoint makes every timed query time out. */
@TestPropertySource(properties = {"omnichannel.contacts.query-timeout.count=1ms",
        "omnichannel.contacts.query-timeout.search=1ms", "omnichannel.contacts.query-timeout.materialise=1ms"})
@Import(MongoTestCommands.class)
class AdvancedFilterTimeoutIT extends ContactApiTestBase {

    @Autowired MongoClient client;

    @AfterEach
    void failpointOff() {
        MongoTestCommands.alwaysTimeOut(client, false);
    }

    @Test
    void previewTimeoutIs504() throws Exception {
        contact(brandA, "Asha", "919800000001");
        Map<String, Object> rules = Map.of("combinator", "and", "not", false, "rules",
                List.of(Map.of("field", "name", "operator", "contains", "value", "a")));
        MongoTestCommands.alwaysTimeOut(client, true);
        send(post(base(brandA) + "/contact-filters/preview"), agentToken, Map.of("rules", rules))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("QUERY_TIMEOUT"))
                .andExpect(jsonPath("$.message").value("This filter takes too long to run. Make it more specific."));
    }

    private String advancedFilter() throws Exception {
        Map<String, Object> rules = Map.of("combinator", "and", "not", false, "rules",
                List.of(Map.of("field", "name", "operator", "contains", "value", "a")));
        return json(send(post(base(brandA) + "/contact-filters"), leadToken, Map.of("name", "F", "rules", rules))
                .andExpect(status().isCreated())).get("id").asString();
    }

    @Test
    void searchTimeoutIs504() throws Exception {
        String id = advancedFilter();
        MongoTestCommands.alwaysTimeOut(client, true);
        send(post(base(brandA) + "/contacts/search"), agentToken, Map.of("filterId", id))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("QUERY_TIMEOUT"));
    }

    @Test
    void countTimeoutIs504() throws Exception {
        String id = advancedFilter();
        MongoTestCommands.alwaysTimeOut(client, true);
        send(post(base(brandA) + "/contacts/count"), agentToken, Map.of("selection", Map.of("filterId", id)))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("QUERY_TIMEOUT"));
    }

    @Test
    void exportTimeoutIs504() throws Exception {
        String id = advancedFilter();
        MongoTestCommands.alwaysTimeOut(client, true);
        send(post(base(brandA) + "/contacts/export"), wmToken, Map.of("filterId", id))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("QUERY_TIMEOUT"));
    }
}
