package me.nageshk.omnichannel.service.billing.document;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.db.billing.BillingOrder;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.CreditNote;
import me.nageshk.omnichannel.db.billing.CreditNoteRepository;
import me.nageshk.omnichannel.db.billing.DocumentLine;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.InvoiceRepository;
import me.nageshk.omnichannel.db.billing.OrderCredit;
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import me.nageshk.omnichannel.security.entitlement.BillingSettingsStore;
import me.nageshk.omnichannel.service.billing.document.DocumentViews.CreditNoteView;
import me.nageshk.omnichannel.service.billing.money.AmountInWords;
import me.nageshk.omnichannel.service.billing.money.FinancialYear;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Credit notes. Each gives back part of one invoice, with that invoice's parties and tax, and is counted on the
 * invoice so that all the notes against it can never exceed what it charged.
 */
@Service
public class CreditNoteService {

    private final CreditNoteRepository creditNotes;
    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final BillingSettingsStore settings;
    private final DocumentNumbers numbers;
    private final MongoTemplate mongo;
    private final Clock clock;

    public CreditNoteService(CreditNoteRepository creditNotes, InvoiceRepository invoices, CustomerRepository customers,
            BillingSettingsStore settings, DocumentNumbers numbers, MongoTemplate mongo, Clock clock) {
        this.creditNotes = creditNotes;
        this.invoices = invoices;
        this.customers = customers;
        this.settings = settings;
        this.numbers = numbers;
        this.mongo = mongo;
        this.clock = clock;
    }

    /**
     * Issues the note for one credit of an order, under the id the order reserved, and counts it on its invoice.
     * Both parts can be done again without effect.
     */
    public CreditNote issue(BillingOrder o, OrderCredit credit) {
        Invoice invoice = invoices.findById(credit.invoiceId())
                .orElseThrow(() -> new IllegalStateException("order " + o.getNumber() + " credits a missing invoice"));
        BillingSettings s = settings.get();
        Instant now = clock.instant();
        String year = FinancialYear.of(now, ZoneId.of(s.getZone()));
        CreditNote note = numbers.insertNumbered(CreditNote.class, credit.creditNoteId(), year, sequence -> {
            CreditNote n = new CreditNote();
            n.setId(credit.creditNoteId());
            n.setFinancialYear(year);
            n.setSequence(sequence);
            n.setNumber(DocumentNumbers.number(s.getCreditNotePrefix(), year, sequence));
            n.setOrderId(o.getId());
            n.setOrderNumber(o.getNumber());
            n.setCustomerId(o.getCustomerId());
            n.setIssuedAt(now);
            n.setSupplier(invoice.getSupplier());
            n.setCustomer(invoice.getCustomer());
            n.setSacCode(invoice.getSacCode());
            n.setLines(List.of(new DocumentLine(credit.description(), credit.taxable())));
            n.setTaxable(credit.taxable());
            n.setTax(credit.tax());
            n.setTotal(credit.total());
            n.setTotalInWords(AmountInWords.rupees(credit.total()));
            n.setInvoiceId(invoice.getId());
            n.setInvoiceNumber(invoice.getNumber());
            n.setInvoiceDate(invoice.getIssuedAt());
            n.setReason(reason(o));
            return n;
        });
        mongo.updateFirst(new Query(Criteria.where("_id").is(invoice.getId()).and("creditNoteIds").ne(note.getId())),
                new Update().inc("credited", new Decimal128(note.getTaxable())).addToSet("creditNoteIds", note.getId()),
                Invoice.class);
        return note;
    }

    /** Why the money comes back, in words for the document. */
    static String reason(BillingOrder o) {
        return switch (o.getKind()) {
            case UPGRADE -> "Unused time on upgrade";
            case CHANGE -> "Unused time on plan change";
            case CANCEL_ADDON -> "Add-on cancelled";
            case CANCEL_PLAN -> "Plan cancelled";
            default -> o.getReason() == null || o.getReason().isBlank() ? "Adjustment" : o.getReason();
        };
    }

    /** Newest first. */
    public List<CreditNoteView> list(String customerId) {
        if (!customers.existsById(customerId)) throw new NotFoundException("customer not found");
        return creditNotes.findByCustomerIdOrderByIssuedAtDescIdDesc(customerId).stream().map(CreditNoteService::view)
                .toList();
    }

    public CreditNoteView get(String id) {
        return view(require(id));
    }

    /** @throws NotFoundException also for another customer's credit note */
    public CreditNoteView getFor(String customerId, String id) {
        return view(requireFor(customerId, id));
    }

    public CreditNote require(String id) {
        return creditNotes.findById(id).orElseThrow(() -> new NotFoundException("credit note not found"));
    }

    public CreditNote requireFor(String customerId, String id) {
        CreditNote n = require(id);
        if (!n.getCustomerId().equals(customerId)) throw new NotFoundException("credit note not found");
        return n;
    }

    private static CreditNoteView view(CreditNote n) {
        return new CreditNoteView(n.getId(), n.getNumber(), n.getIssuedAt(), n.getOrderId(), n.getInvoiceId(),
                n.getInvoiceNumber(), n.getInvoiceDate(), n.getSupplier(), n.getCustomer(), n.getLines(), n.getTaxable(),
                n.getTax(), n.getTotal(), n.getTotalInWords(), n.getReason(), true);
    }
}
