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

/** Spec C15: a report that hits the 10 s limit answers 503 REPORT_TIMEOUT, whichever read runs out of time. */
@Import(MongoTestCommands.class)
class ReportTimeoutIT extends CrmTestBase {

    @Autowired MongoClient client;

    @AfterEach
    void failpointOff() {
        MongoTestCommands.alwaysTimeOut(client, false);
    }

    @Test
    void reportTimeoutIs503() throws Exception {
        enableCrm();
        captured(contact("Asha", "919900000651"), number);
        MongoTestCommands.alwaysTimeOut(client, true);
        for (String name : List.of("pipeline-summary", "deals-over-time", "activity-summary", "grid", "campaigns")) {
            Map<String, Object> body = name.equals("grid") ? Map.of("tz", "Asia/Kolkata", "entityType", "DEAL",
                    "groupBy", "owner") : Map.of("tz", "Asia/Kolkata");
            send(post(crm() + "/reports/" + name), managerToken, body)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("REPORT_TIMEOUT"));
        }
        MongoTestCommands.alwaysTimeOut(client, false);
        send(post(crm() + "/reports/pipeline-summary"), managerToken, Map.of("tz", "Asia/Kolkata"))
                .andExpect(status().isOk());
    }
}
