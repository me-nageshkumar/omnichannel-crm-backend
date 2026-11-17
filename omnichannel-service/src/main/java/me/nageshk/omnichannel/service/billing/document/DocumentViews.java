package me.nageshk.omnichannel.service.billing.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.billing.DocumentLine;
import me.nageshk.omnichannel.db.billing.DocumentParty;
import me.nageshk.omnichannel.db.billing.TaxAmounts;
import me.nageshk.omnichannel.service.billing.order.OrderViews.PaymentView;

/** What the billing API returns for invoices and credit notes. */
public final class DocumentViews {

    private DocumentViews() {}

    /**
     * @param accountCreditUsed account credit set against the total; {@code amountPaid} is the rest
     * @param credited the taxable value credit notes have given back on this invoice
     */
    public record InvoiceView(String id, String number, Instant issuedAt, String orderId, String orderNumber,
            DocumentParty supplier, DocumentParty customer, String sacCode, List<DocumentLine> lines, BigDecimal taxable,
            TaxAmounts tax, BigDecimal total, String totalInWords, BigDecimal accountCreditUsed, BigDecimal amountPaid,
            PaymentView payment, BigDecimal credited, boolean hasPdf) {}

    public record CreditNoteView(String id, String number, Instant issuedAt, String orderId, String invoiceId,
            String invoiceNumber, Instant invoiceDate, DocumentParty supplier, DocumentParty customer,
            List<DocumentLine> lines, BigDecimal taxable, TaxAmounts tax, BigDecimal total, String totalInWords,
            String reason, boolean hasPdf) {}
}
