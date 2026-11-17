package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import me.nageshk.omnichannel.db.billing.BillingOrder;
import me.nageshk.omnichannel.db.billing.BillingOrderRepository;
import me.nageshk.omnichannel.db.billing.BillingProfile;
import me.nageshk.omnichannel.db.billing.CreditNote;
import me.nageshk.omnichannel.db.billing.CreditNoteRepository;
import me.nageshk.omnichannel.db.billing.DocumentLine;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.InvoiceRepository;
import me.nageshk.omnichannel.db.billing.OrderLine;
import me.nageshk.omnichannel.db.billing.TaxAmounts;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class MoneyDocumentsIT {

    @Autowired BillingOrderRepository orders;
    @Autowired InvoiceRepository invoices;
    @Autowired CreditNoteRepository creditNotes;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clean() {
        for (String c : List.of("billingOrders", "invoices", "creditNotes")) mongo.remove(new Query(), c);
    }

    private static final TaxAmounts TAX =
            new TaxAmounts(new BigDecimal("18"), new BigDecimal("81.00"), new BigDecimal("81.00"), new BigDecimal("0.00"), false);

    private Invoice invoice(String orderId, int sequence) {
        Invoice i = new Invoice();
        i.setNumber("INV/26-27/" + String.format("%06d", sequence));
        i.setFinancialYear("2026-27");
        i.setSequence(sequence);
        i.setOrderId(orderId);
        i.setCustomerId("c1");
        i.setIssuedAt(Instant.parse("2026-12-01T05:00:00Z"));
        i.setLines(List.of(new DocumentLine("Gold, monthly", new BigDecimal("900.00"))));
        i.setTaxable(new BigDecimal("900.00"));
        i.setTax(TAX);
        i.setTotal(new BigDecimal("1062.00"));
        return i;
    }

    @Test
    void anOrderKeepsItsAmountsAsDecimals() {
        BillingOrder o = new BillingOrder();
        o.setNumber("ORD-000001");
        o.setCustomerId("c1");
        o.setKind(OrderKind.NEW);
        o.setStatus(OrderStatus.REQUESTED);
        o.setLines(List.of(new OrderLine("PLAN", "Gold, monthly", null, null, 0, new BigDecimal("1000.00"), new BigDecimal("900.00"))));
        o.setTaxable(new BigDecimal("900.00"));
        o.setTax(TAX);
        o.setTotal(new BigDecimal("1062.00"));
        o.setPayable(new BigDecimal("1062.00"));
        orders.save(o);

        BillingOrder read = orders.findById(o.getId()).orElseThrow();
        assertThat(read.getLines().get(0).amount()).isEqualByComparingTo("900.00");
        assertThat(read.getTax().cgst()).isEqualByComparingTo("81.00");
        assertThat(read.getTax().total()).isEqualByComparingTo("162.00");
        Document raw = mongo.getCollection("billingOrders").find().first();
        assertThat(raw.get("total")).isInstanceOf(Decimal128.class);
        assertThat(raw.get("tax", Document.class).get("cgst")).isInstanceOf(Decimal128.class);
        assertThat(raw.getList("lines", Document.class).get(0).get("amount")).isInstanceOf(Decimal128.class);

        BillingOrder twin = new BillingOrder();
        twin.setNumber("ORD-000001");
        assertThatThrownBy(() -> orders.save(twin)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void anOrderHasOneInvoiceAndANumberIsUsedOnce() {
        invoices.save(invoice("o1", 1));
        assertThatThrownBy(() -> invoices.save(invoice("o1", 2))).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> invoices.save(invoice("o2", 1))).isInstanceOf(DuplicateKeyException.class);
        invoices.save(invoice("o2", 2));
        assertThat(invoices.count()).isEqualTo(2);
        assertThat(mongo.getCollection("invoices").find().first().get("taxable")).isInstanceOf(Decimal128.class);
    }

    @Test
    void creditNoteNumbersAreTheirOwnSeries() {
        CreditNote n = new CreditNote();
        n.setNumber("CN/26-27/000001");
        n.setFinancialYear("2026-27");
        n.setSequence(1);
        n.setInvoiceId("i1");
        n.setTaxable(new BigDecimal("464.52"));
        creditNotes.save(n);
        CreditNote twin = new CreditNote();
        twin.setFinancialYear("2026-27");
        twin.setSequence(1);
        assertThatThrownBy(() -> creditNotes.save(twin)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aProfileIsCompleteWithNameAddressAndState() {
        assertThat(new BillingProfile("Acme Pvt Ltd", "1 MG Road, Bengaluru", "29", null).complete()).isTrue();
        assertThat(new BillingProfile("Acme Pvt Ltd", " ", "29", null).complete()).isFalse();
        assertThat(new BillingProfile(null, "1 MG Road", "29", "29ABCDE1234F1Z5").complete()).isFalse();
    }
}
