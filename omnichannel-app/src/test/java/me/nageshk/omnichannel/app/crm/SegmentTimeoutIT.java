package me.nageshk.omnichannel.app.crm;

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

/** Spec D C5: a segment query that hits the 10 s limit answers 503 SEGMENT_TIMEOUT. */
@Import(MongoTestCommands.class)
class SegmentTimeoutIT extends CrmTestBase {

    @Autowired MongoClient client;

    @AfterEach
    void failpointOff() {
        MongoTestCommands.alwaysTimeOut(client, false);
    }

    @Test
    void timeoutIsA503() throws Exception {
        enableCrm();
        captured(contact("Asha", "919900000961"), number);
        Map<String, Object> rules = Map.of("combinator", "and", "not", false, "rules", List.of(
                Map.of("field", "contact.lifecycleStatus", "operator", "is", "value", "lead")));
        MongoTestCommands.alwaysTimeOut(client, true);
        send(post(crm() + "/segments/preview"), managerToken, Map.of("rules", rules))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SEGMENT_TIMEOUT"));
        MongoTestCommands.alwaysTimeOut(client, false);
        send(post(crm() + "/segments/preview"), managerToken, Map.of("rules", rules)).andExpect(status().isOk());
    }
}
