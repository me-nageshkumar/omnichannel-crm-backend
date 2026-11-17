package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.InvoiceRepository;
import me.nageshk.omnichannel.service.billing.document.DocumentNumbers;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Invoice numbers run 1, 2, 3 within a financial year, with no gap and no duplicate, whoever issues at once. */
class DocumentNumbersIT extends MoneyApiTestBase {

    @Autowired DocumentNumbers numbers;
    @Autowired InvoiceRepository invoices;

    @Test
    void invoicesIssuedAtOnceGetConsecutiveNumbers() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Invoice>> issued = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String id = new ObjectId().toHexString();
            issued.add(pool.submit(() -> numbers.insertNumbered(Invoice.class, id, "2026-27", sequence -> {
                Invoice inv = new Invoice();
                inv.setId(id);
                inv.setOrderId("order-" + id);
                inv.setFinancialYear("2026-27");
                inv.setSequence(sequence);
                inv.setNumber(DocumentNumbers.number("INV", "2026-27", sequence));
                return inv;
            })));
        }
        List<Integer> sequences = new ArrayList<>();
        for (Future<Invoice> f : issued) sequences.add(f.get(60, TimeUnit.SECONDS).getSequence());
        pool.shutdown();

        List<Integer> oneToTwenty = new ArrayList<>();
        for (int i = 1; i <= 20; i++) oneToTwenty.add(i);
        assertThat(sequences).containsExactlyInAnyOrderElementsOf(oneToTwenty);
        assertThat(invoices.count()).isEqualTo(20);
        assertThat(invoices.findAll()).anyMatch(i -> i.getNumber().equals("INV/2026-27/000020"));
    }

    @Test
    void theSameDocumentInsertedAgainKeepsItsNumber() {
        String id = new ObjectId().toHexString();
        java.util.function.IntFunction<Invoice> make = sequence -> {
            Invoice inv = new Invoice();
            inv.setId(id);
            inv.setOrderId("order-" + id);
            inv.setFinancialYear("2026-27");
            inv.setSequence(sequence);
            return inv;
        };
        assertThat(numbers.insertNumbered(Invoice.class, id, "2026-27", make).getSequence()).isEqualTo(1);
        assertThat(numbers.insertNumbered(Invoice.class, id, "2026-27", make).getSequence()).isEqualTo(1);
        assertThat(invoices.count()).isEqualTo(1);
    }

    @Test
    void theNumbersStartAgainOnTheFirstOfApril() throws Exception {
        // one second before midnight of 1 April 2027 in India, and that midnight
        clock.set(Instant.parse("2027-03-31T18:29:59Z"));
        tokens();
        assertThat(buy(acme, request("NEW", "gold-monthly")).get("invoiceNumber").asString()).isEqualTo("INV/2026-27/000001");
        clock.set(Instant.parse("2027-03-31T18:30:00Z"));
        tokens();
        assertThat(buy(bolt, request("NEW", "gold-monthly")).get("invoiceNumber").asString()).isEqualTo("INV/2027-28/000001");
    }
}
