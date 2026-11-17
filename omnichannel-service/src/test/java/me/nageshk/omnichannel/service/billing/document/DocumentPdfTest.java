package me.nageshk.omnichannel.service.billing.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.db.billing.CreditNote;
import me.nageshk.omnichannel.db.billing.DocumentLine;
import me.nageshk.omnichannel.db.billing.DocumentParty;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.OrderPayment;
import me.nageshk.omnichannel.db.billing.TaxAmounts;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class DocumentPdfTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final DocumentParty SUPPLIER = new DocumentParty("Omni Cloud Pvt Ltd", "12 Residency Road\nBengaluru 560025",
            "29", "Karnataka", "29AAACO1234A1Z5");
    static final DocumentParty ACME = new DocumentParty("Acme Retail Pvt Ltd", "1 MG Road, Bengaluru 560001", "29",
            "Karnataka", "29ABCDE1234F1Z5");

    static Invoice invoice() {
        Invoice inv = new Invoice();
        inv.setId("i1");
        inv.setNumber("INV/2026-27/000001");
        inv.setOrderNumber("ORD-000001");
        inv.setIssuedAt(Instant.parse("2026-12-01T05:00:00Z"));
        inv.setSupplier(SUPPLIER);
        inv.setCustomer(ACME);
        inv.setSacCode("998314");
        inv.setLines(List.of(new DocumentLine("Gold, monthly (1 Dec 2026 – 31 Dec 2026)", new BigDecimal("900.00"))));
        inv.setTaxable(new BigDecimal("900.00"));
        inv.setTax(new TaxAmounts(new BigDecimal("18"), new BigDecimal("81.00"), new BigDecimal("81.00"), BigDecimal.ZERO, false));
        inv.setTotal(new BigDecimal("1062.00"));
        inv.setTotalInWords("Rupees One Thousand Sixty-Two Only");
        inv.setAmountPaid(new BigDecimal("1062.00"));
        inv.setPayment(new OrderPayment("UPI", "UTR-778899", LocalDate.of(2026, 12, 1), null, "u1", inv.getIssuedAt()));
        return inv;
    }

    static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    static int pages(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    @Test
    void anInvoiceSaysEverythingATaxInvoiceMust() throws Exception {
        byte[] pdf = DocumentPdf.invoice(invoice(), IST);
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = text(pdf);
        assertThat(text).contains("Tax invoice", "INV/2026-27/000001", "1 Dec 2026", "Omni Cloud Pvt Ltd",
                "Acme Retail Pvt Ltd", "29AAACO1234A1Z5", "29ABCDE1234F1Z5", "Karnataka (29)", "998314",
                "Gold, monthly (1 Dec 2026 – 31 Dec 2026)", "900.00", "CGST 9%", "SGST 9%", "81.00", "1,062.00",
                "Rupees One Thousand Sixty-Two Only", "UPI", "UTR-778899", "This is a computer-generated document.");
        assertThat(text).doesNotContain("IGST");
        assertThat(pages(pdf)).isEqualTo(1);
    }

    @Test
    void anInterStateInvoiceShowsIgstAndTheCreditUsed() throws Exception {
        Invoice inv = invoice();
        inv.setCustomer(new DocumentParty("Bolt Traders", "9 FC Road, Pune 411004", "27", "Maharashtra", null));
        inv.setTax(new TaxAmounts(new BigDecimal("18"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("162.00"), true));
        inv.setAccountCreditUsed(new BigDecimal("500.00"));
        inv.setAmountPaid(new BigDecimal("562.00"));
        String text = text(DocumentPdf.invoice(inv, IST));
        assertThat(text).contains("IGST 18%", "162.00", "Maharashtra (27)", "Account credit used", "500.00", "562.00");
        assertThat(text).doesNotContain("CGST").doesNotContain("SGST");
    }

    @Test
    void aLargeAmountIsGroupedTheIndianWay() throws Exception {
        Invoice inv = invoice();
        inv.setTotal(new BigDecimal("1234567.50"));
        assertThat(text(DocumentPdf.invoice(inv, IST))).contains("12,34,567.50");
    }

    @Test
    void aCreditNoteNamesItsInvoiceAndItsReason() throws Exception {
        CreditNote n = new CreditNote();
        n.setNumber("CN/2026-27/000001");
        n.setIssuedAt(Instant.parse("2026-12-16T05:00:00Z"));
        n.setSupplier(SUPPLIER);
        n.setCustomer(ACME);
        n.setSacCode("998314");
        n.setLines(List.of(new DocumentLine("Unused value on invoice INV/2026-27/000001", new BigDecimal("464.52"))));
        n.setTaxable(new BigDecimal("464.52"));
        n.setTax(new TaxAmounts(new BigDecimal("18"), new BigDecimal("41.81"), new BigDecimal("41.80"), BigDecimal.ZERO, false));
        n.setTotal(new BigDecimal("548.13"));
        n.setTotalInWords("Rupees Five Hundred Forty-Eight and Thirteen Paise Only");
        n.setInvoiceNumber("INV/2026-27/000001");
        n.setInvoiceDate(Instant.parse("2026-12-01T05:00:00Z"));
        n.setReason("Unused time on upgrade");
        String text = text(DocumentPdf.creditNote(n, IST));
        assertThat(text).contains("Credit note", "CN/2026-27/000001", "16 Dec 2026", "Against invoice INV/2026-27/000001",
                "1 Dec 2026", "Unused time on upgrade", "464.52", "41.81", "41.80", "548.13");
        assertThat(text).doesNotContain("Tax invoice");
    }

    @Test
    void aLongInvoiceRunsOntoMorePages() throws Exception {
        Invoice inv = invoice();
        List<DocumentLine> lines = new ArrayList<>();
        for (int i = 1; i <= 40; i++) lines.add(new DocumentLine("Add-on number " + i + " for the term", new BigDecimal("10.00")));
        inv.setLines(lines);
        byte[] pdf = DocumentPdf.invoice(inv, IST);
        assertThat(pages(pdf)).isGreaterThan(1);
        String text = text(pdf);
        for (int i = 1; i <= 40; i++) assertThat(text).contains("Add-on number " + i + " for the term");
        assertThat(text).contains("Rupees One Thousand Sixty-Two Only");
    }

    @Test
    void textTheFontCannotDrawDoesNotBreakTheDocument() throws Exception {
        Invoice inv = invoice();
        inv.setCustomer(new DocumentParty("अक्मे रिटेल \u0007 Ltd", "A very long address line that goes on and on, "
                + "building after building, street after street, far past the width of the column it is printed in, 560001",
                "29", "Karnataka", null));
        String text = text(DocumentPdf.invoice(inv, IST));
        assertThat(text).contains("Ltd", "560001", "INV/2026-27/000001");
    }
}
