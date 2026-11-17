package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** A credit note reverses part of one invoice, on that invoice's own terms. */
class CreditNotesIT extends MoneyApiTestBase {

    @Test
    void aCreditNoteCarriesItsInvoicesTaxAndIsSeenByItsCustomerOnly() throws Exception {
        buy(bolt, request("NEW", "gold-monthly"));
        // the rate changes after the invoice was issued
        Map<String, Object> s = settings();
        s.put("gstRatePercent", 12);
        s.put("version", json(GET("/api/billing/settings", superToken)).get("version").asLong());
        PUT("/api/billing/settings", superToken, s).andExpect(status().isOk());

        on(day(2026, 12, 16));
        JsonNode order = raise(bolt, request("UPGRADE", "platinum-monthly"));
        // the new term at the new rate
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("1680.00");
        JsonNode paid = json(pay(order.get("id").asString()).andExpect(status().isOk()));
        String noteId = paid.at("/creditNoteIds/0").asString();

        JsonNode note = json(GET("/api/billing/credit-notes/" + noteId, amToken).andExpect(status().isOk()));
        assertThat(note.get("taxable").decimalValue()).isEqualByComparingTo("464.52");
        assertThat(note.at("/tax/ratePercent").decimalValue()).isEqualByComparingTo("18");
        assertThat(note.at("/tax/interState").asBoolean()).isTrue();
        assertThat(note.at("/tax/igst").decimalValue()).isEqualByComparingTo("83.61");
        assertThat(note.at("/tax/cgst").decimalValue()).isEqualByComparingTo("0");
        assertThat(note.get("total").decimalValue()).isEqualByComparingTo("548.13");
        assertThat(note.at("/customer/stateName").asString()).isEqualTo("Maharashtra");
        assertThat(note.get("hasPdf").asBoolean()).isTrue();

        GET("/api/my/billing/credit-notes", boltToken).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].number").value("CN/26-27/000001"));
        GET("/api/my/billing/credit-notes/" + noteId, boltToken).andExpect(status().isOk());
        GET("/api/my/billing/credit-notes", adminToken).andExpect(jsonPath("$.length()").value(0));
        GET("/api/my/billing/credit-notes/" + noteId, adminToken).andExpect(status().isNotFound());
        GET("/api/billing/credit-notes/" + noteId, boltToken).andExpect(status().isForbidden());
        GET("/api/billing/customers/" + bolt.getId() + "/credit-notes", boltToken).andExpect(status().isForbidden());
        GET("/api/billing/credit-notes/000000000000000000000000", amToken).andExpect(status().isNotFound());
    }
}
