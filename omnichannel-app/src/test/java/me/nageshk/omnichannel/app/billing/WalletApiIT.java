package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import me.nageshk.omnichannel.common.billing.UsageSource;
import me.nageshk.omnichannel.common.billing.WalletEntryType;
import me.nageshk.omnichannel.db.billing.WalletEntry;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.service.billing.wallet.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Reading a wallet, and what staff may do to it by hand. */
class WalletApiIT extends MoneyApiTestBase {

    @Autowired WalletService wallets;

    private String staff(String tail) {
        return "/api/billing/customers/" + acme.getId() + "/wallet" + tail;
    }

    private WalletEntry usage(String ws) {
        WalletEntry e = new WalletEntry();
        e.setType(WalletEntryType.USAGE);
        e.setMeter(UsageMeter.WHATSAPP_MESSAGE);
        e.setSource(UsageSource.CONSOLE);
        e.setWorkspaceId(ws);
        return e;
    }

    private static Map<String, Object> adjustment(Object amount, String note) {
        Map<String, Object> b = new HashMap<>();
        b.put("amount", amount);
        if (note != null) b.put("note", note);
        return b;
    }

    @Test
    void aCustomerThatNeverToppedUpHasAnEmptyWallet() throws Exception {
        GET(staff(""), amToken).andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(0.0))
                .andExpect(jsonPath("$.low").value(false)).andExpect(jsonPath("$.lowBalanceAt").doesNotExist())
                .andExpect(jsonPath("$.messageRate").doesNotExist()).andExpect(jsonPath("$.refundable").value(0.0));
        GET(staff("/entries"), amToken).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items.length()").value(0));
        GET(staff("/usage"), amToken).andExpect(jsonPath("$.length()").value(0));
        GET("/api/my/billing/wallet", adminToken).andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(0.0));
        GET("/api/billing/customers/000000000000000000000000/wallet", amToken).andExpect(status().isNotFound());
    }

    @Test
    void staffAdjustTheWalletWithANote() throws Exception {
        POST(staff("/adjustments"), amToken, adjustment(1000, "Opening balance")).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1000.0));
        // adjustment money was never paid for, so none of it can be refunded
        GET(staff(""), amToken).andExpect(jsonPath("$.refundable").value(0.0));
        POST(staff("/adjustments"), amToken, adjustment(-999.8, "test")).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0.2));
        POST(staff("/adjustments"), amToken, adjustment(-0.2001, "too much")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_ENOUGH_WALLET"));
        POST(staff("/adjustments"), amToken, adjustment(0, "nothing")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.amount").exists());
        POST(staff("/adjustments"), amToken, adjustment(1.00005, "five decimals")).andExpect(status().isBadRequest());
        POST(staff("/adjustments"), amToken, adjustment(5, " ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.note").exists());
        GET(staff(""), amToken).andExpect(jsonPath("$.balance").value(0.2));

        JsonNode page = json(GET(staff("/entries"), amToken));
        assertThat(page.get("total").asInt()).isEqualTo(2);
        assertThat(page.at("/items/0/type").asString()).isEqualTo("ADJUSTMENT");
        assertThat(page.at("/items/0/note").asString()).isIn("test", "Opening balance");
        assertThat(page.at("/items/0/byName").asString()).isNotBlank();
        // the customer sees the movement, not who made it or why
        JsonNode mine = json(GET("/api/my/billing/wallet/entries", adminToken).andExpect(status().isOk()));
        assertThat(mine.get("total").asInt()).isEqualTo(2);
        assertThat(mine.at("/items/0/byName").isNull()).isTrue();
        assertThat(mine.at("/items/0/note").isNull()).isTrue();
        // and the change is in the customer's billing history
        JsonNode events = json(GET("/api/billing/customers/" + acme.getId() + "/subscription/events", amToken));
        List<String> kinds = new java.util.ArrayList<>();
        events.forEach(e -> kinds.add(e.get("kind").asString()));
        assertThat(kinds).contains("WALLET_ADJUSTED");
    }

    @Test
    void usageIsTotalledByDayAndSourceWithReversalsTakenOff() throws Exception {
        wallets.credit(acme.getId(), new BigDecimal("1000"), usageless(WalletEntryType.TOPUP));
        for (int i = 0; i < 4; i++) wallets.debit(acme.getId(), new BigDecimal("0.25"), usage(ws.getId()));
        WalletEntry back = usage(ws.getId());
        back.setType(WalletEntryType.REVERSAL);
        wallets.credit(acme.getId(), new BigDecimal("0.25"), back);
        WalletEntry campaign = usage(ws.getId());
        campaign.setSource(UsageSource.CAMPAIGN);
        wallets.debit(acme.getId(), new BigDecimal("0.25"), campaign);

        JsonNode days = json(GET(staff("/usage?days=7"), amToken).andExpect(status().isOk()));
        assertThat(days).hasSize(2);
        assertThat(days.at("/0/day").asString()).isEqualTo("2026-12-01");
        assertThat(days.at("/0/source").asString()).isEqualTo("CAMPAIGN");
        assertThat(days.at("/0/messages").asInt()).isEqualTo(1);
        assertThat(days.at("/1/source").asString()).isEqualTo("CONSOLE");
        assertThat(days.at("/1/messages").asInt()).isEqualTo(3);
        assertThat(days.at("/1/amount").decimalValue()).isEqualByComparingTo("0.75");
        GET("/api/my/billing/wallet/usage?days=7", adminToken).andExpect(jsonPath("$.length()").value(2));
        GET(staff("/usage?days=91"), amToken).andExpect(status().isBadRequest());
        // yesterday's window does not hold today... but a one-day window holds today
        GET(staff("/usage?days=1"), amToken).andExpect(jsonPath("$.length()").value(2));

        JsonNode page = json(GET(staff("/entries"), amToken));
        assertThat(page.get("total").asInt()).isEqualTo(7);
        assertThat(page.at("/items/0/workspaceName").asString()).isEqualTo("Main");
    }

    private static WalletEntry usageless(WalletEntryType type) {
        WalletEntry e = new WalletEntry();
        e.setType(type);
        return e;
    }

    @Test
    void theLowBalanceWarningIsSetByStaff() throws Exception {
        billing.wallet(acme.getId(), "50.0000");
        PUT(staff("/threshold"), amToken, Map.of("lowBalanceAt", 100)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lowBalanceAt").value(100.0)).andExpect(jsonPath("$.low").value(true))
                .andExpect(jsonPath("$.balance").value(50.0));
        PUT(staff("/threshold"), amToken, Map.of("lowBalanceAt", 20)).andExpect(jsonPath("$.low").value(false));
        PUT(staff("/threshold"), amToken, Map.of("lowBalanceAt", -1)).andExpect(status().isBadRequest());
        Map<String, Object> none = new HashMap<>();
        none.put("lowBalanceAt", null);
        PUT(staff("/threshold"), amToken, none).andExpect(status().isOk()).andExpect(jsonPath("$.lowBalanceAt").doesNotExist());
        // a threshold for a customer with no wallet yet creates an empty one
        PUT("/api/billing/customers/" + bolt.getId() + "/wallet/threshold", amToken, Map.of("lowBalanceAt", 100))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(0.0)).andExpect(jsonPath("$.low").value(true));
    }

    @Test
    void whoMaySeeAndChangeAWallet() throws Exception {
        GET(staff(""), adminToken).andExpect(status().isForbidden());
        POST(staff("/adjustments"), adminToken, adjustment(5, "x")).andExpect(status().isForbidden());
        PUT(staff("/threshold"), adminToken, Map.of("lowBalanceAt", 1)).andExpect(status().isForbidden());
        String manager = testUsers.accessToken(testUsers.createInCustomer(acme.getId(), "money-manager", null,
                List.of(new WorkspaceRole(ws.getId(), testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER)))));
        GET("/api/my/billing/wallet", manager).andExpect(status().isForbidden());
        GET("/api/my/billing/wallet/entries", manager).andExpect(status().isForbidden());
        GET("/api/my/billing/wallet", amToken).andExpect(status().isForbidden());
    }
}
