package me.nageshk.omnichannel.app.billing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import me.nageshk.omnichannel.service.billing.BillingAudit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Money that moved is reported as moved, even when the line about it could not be written. */
class WalletAuditIT extends MoneyApiTestBase {

    @MockitoSpyBean BillingAudit audit;

    @Test
    void anAdjustmentWhoseHistoryLineFailsStillAnswersWithTheNewBalance() throws Exception {
        doThrow(new IllegalStateException("history is down")).when(audit)
                .record(any(), any(), eq("WALLET_ADJUSTED"), any(), any(), any(), any());
        String path = "/api/billing/customers/" + acme.getId() + "/wallet";
        // a 500 here would invite a second try, and a second adjustment
        POST(path + "/adjustments", amToken, Map.of("amount", 100, "note", "Opening balance")).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.0));
        GET(path, amToken).andExpect(jsonPath("$.balance").value(100.0));
    }
}
