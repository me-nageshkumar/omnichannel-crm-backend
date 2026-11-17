package me.nageshk.omnichannel.service.billing.document;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.billing.IndianStates;
import me.nageshk.omnichannel.db.billing.BillingOrder;
import me.nageshk.omnichannel.db.billing.BillingProfile;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.DocumentLine;
import me.nageshk.omnichannel.db.billing.DocumentParty;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.InvoiceRepository;
import me.nageshk.omnichannel.db.billing.OrderPayment;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.security.entitlement.BillingSettingsStore;
import me.nageshk.omnichannel.service.billing.document.DocumentViews.InvoiceView;
import me.nageshk.omnichannel.service.billing.money.AmountInWords;
import me.nageshk.omnichannel.service.billing.money.FinancialYear;
import me.nageshk.omnichannel.service.billing.order.OrderViews.PaymentView;
import org.springframework.stereotype.Service;

/**
 * Tax invoices. One is issued for every activated order that charges something, as a snapshot of the supplier, the
 * customer and the amounts at that moment; nothing changes it afterwards except what credit notes give back on it.
 */
@Service
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final UserRepository users;
    private final BillingSettingsStore settings;
    private final DocumentNumbers numbers;
    private final Clock clock;

    public InvoiceService(InvoiceRepository invoices, CustomerRepository customers, UserRepository users,
            BillingSettingsStore settings, DocumentNumbers numbers, Clock clock) {
        this.invoices = invoices;
        this.customers = customers;
        this.users = users;
        this.settings = settings;
        this.numbers = numbers;
        this.clock = clock;
    }

    /**
     * The order's invoice, inserted under the id the order reserved; the one already there if this ran before.
     *
     * @throws IllegalStateException for an order that reserved no invoice (nothing to charge)
     */
    public Invoice issue(BillingOrder o) {
        if (o.getInvoiceId() == null) throw new IllegalStateException("order " + o.getNumber() + " has nothing to invoice");
        BillingSettings s = settings.get();
        Instant now = clock.instant();
        String year = FinancialYear.of(now, ZoneId.of(s.getZone()));
        Customer customer = customers.findById(o.getCustomerId()).orElse(null);
        BillingProfile profile = customer == null ? null : customer.getBilling();
        return numbers.insertNumbered(Invoice.class, o.getInvoiceId(), year, sequence -> {
            Invoice inv = new Invoice();
            inv.setId(o.getInvoiceId());
            inv.setFinancialYear(year);
            inv.setSequence(sequence);
            inv.setNumber(DocumentNumbers.number(s.getInvoicePrefix(), year, sequence));
            inv.setOrderId(o.getId());
            inv.setOrderNumber(o.getNumber());
            inv.setCustomerId(o.getCustomerId());
            inv.setIssuedAt(now);
            inv.setSupplier(party(s.getSupplierLegalName(), s.getSupplierAddress(), s.getSupplierStateCode(),
                    s.getSupplierGstin()));
            inv.setCustomer(profile == null
                    ? party(customer == null ? "Unknown customer" : customer.getName(), null, null, null)
                    : party(profile.legalName(), profile.address(), profile.stateCode(), profile.gstin()));
            inv.setSacCode(s.getSacCode());
            inv.setLines(o.getLines().stream().map(l -> new DocumentLine(l.description(), l.amount())).toList());
            inv.setTaxable(o.getTaxable());
            inv.setTax(o.getTax());
            inv.setTotal(o.getTotal());
            inv.setTotalInWords(AmountInWords.rupees(o.getTotal()));
            inv.setAccountCreditUsed(o.getAccountCreditUsed());
            inv.setAmountPaid(o.getPayable());
            inv.setPayment(o.getPayment());
            return inv;
        });
    }

    /** Newest first. */
    public List<InvoiceView> list(String customerId) {
        if (!customers.existsById(customerId)) throw new NotFoundException("customer not found");
        Map<String, String> names = new HashMap<>();
        return invoices.findByCustomerIdOrderByIssuedAtDescIdDesc(customerId).stream().map(i -> view(i, names)).toList();
    }

    public InvoiceView get(String id) {
        return view(require(id), new HashMap<>());
    }

    /** @throws NotFoundException also for another customer's invoice */
    public InvoiceView getFor(String customerId, String id) {
        return view(requireFor(customerId, id), new HashMap<>());
    }

    public Invoice require(String id) {
        return invoices.findById(id).orElseThrow(() -> new NotFoundException("invoice not found"));
    }

    public Invoice requireFor(String customerId, String id) {
        Invoice inv = require(id);
        if (!inv.getCustomerId().equals(customerId)) throw new NotFoundException("invoice not found");
        return inv;
    }

    static DocumentParty party(String legalName, String address, String stateCode, String gstin) {
        return new DocumentParty(legalName, address, stateCode,
                stateCode == null ? null : IndianStates.nameOf(stateCode).orElse(null), gstin);
    }

    private InvoiceView view(Invoice i, Map<String, String> names) {
        OrderPayment p = i.getPayment();
        PaymentView payment = p == null ? null : new PaymentView(p.method(), p.reference(), p.receivedOn(), p.note(),
                names.computeIfAbsent(p.recordedBy() == null ? "" : p.recordedBy(), this::userName), p.recordedAt());
        return new InvoiceView(i.getId(), i.getNumber(), i.getIssuedAt(), i.getOrderId(), i.getOrderNumber(),
                i.getSupplier(), i.getCustomer(), i.getSacCode(), i.getLines(), i.getTaxable(), i.getTax(), i.getTotal(),
                i.getTotalInWords(), i.getAccountCreditUsed(), i.getAmountPaid(), payment, i.getCredited(), true);
    }

    private String userName(String userId) {
        if (userId.isEmpty() || "system".equals(userId)) return "System";
        return users.findById(userId).map(User::getName).orElse("Unknown user");
    }
}
