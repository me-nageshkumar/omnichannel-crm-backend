package me.nageshk.omnichannel.service.billing.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.OrderCredit;
import me.nageshk.omnichannel.db.billing.TaxAmounts;
import org.junit.jupiter.api.Test;

/** All the credit notes against an invoice together give back no more tax than the invoice charged. */
class CreditTaxCapTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static Invoice invoice() {
        Invoice inv = new Invoice();
        inv.setId("i1");
        inv.setNumber("INV/26-27/000001");
        inv.setTaxable(bd("1200.00"));
        inv.setTax(new TaxAmounts(bd("18"), bd("108.00"), bd("108.00"), bd("0.00"), false));
        return inv;
    }

    @Test
    void theNoteThatTakesTheLastOfAnInvoiceGetsExactlyTheTaxThatIsLeft() {
        Invoice inv = invoice();
        // a first note on 154.75 gave back 27.86 (27.855 rounded up)
        OrderCredit first = UnusedCredit.forAmounts(Map.of("i1", bd("154.75")), id -> inv).get(0);
        assertThat(first.tax().total()).isEqualByComparingTo("27.86");
        inv.setCredited(bd("154.75"));
        inv.setCreditedCgst(first.tax().cgst());
        inv.setCreditedSgst(first.tax().sgst());

        List<OrderCredit> rest = UnusedCredit.forAmounts(Map.of("i1", bd("1045.25")), id -> inv);
        assertThat(rest).hasSize(1);
        assertThat(rest.get(0).taxable()).isEqualByComparingTo("1045.25");
        // 188.145 would round to 188.15 and make 216.01 in all
        assertThat(rest.get(0).tax().total()).isEqualByComparingTo("188.14");
        assertThat(rest.get(0).tax().cgst().add(first.tax().cgst())).isEqualByComparingTo("108.00");
        assertThat(rest.get(0).tax().sgst().add(first.tax().sgst())).isEqualByComparingTo("108.00");
        assertThat(rest.get(0).total()).isEqualByComparingTo("1233.39");
    }

    @Test
    void anOrdinaryNoteIsTaxedAtTheInvoicesRate() {
        OrderCredit c = UnusedCredit.forAmounts(Map.of("i1", bd("464.52")), id -> invoice()).get(0);
        assertThat(c.tax().cgst()).isEqualByComparingTo("41.81");
        assertThat(c.tax().sgst()).isEqualByComparingTo("41.80");
    }
}
