package me.nageshk.omnichannel.app.billing;

import java.util.List;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.billing.IndianStates;
import me.nageshk.omnichannel.common.api.ReadOnlySafe;
import me.nageshk.omnichannel.db.billing.CreditNote;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.billing.BillingProfileService;
import me.nageshk.omnichannel.service.billing.SubscriptionService;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.ProfileRequest;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.ProfileView;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.StateView;
import me.nageshk.omnichannel.service.billing.document.BillingDocumentFiles;
import me.nageshk.omnichannel.service.billing.document.CreditNoteService;
import me.nageshk.omnichannel.service.billing.document.DocumentViews.CreditNoteView;
import me.nageshk.omnichannel.service.billing.document.DocumentViews.InvoiceView;
import me.nageshk.omnichannel.service.billing.document.InvoiceService;
import me.nageshk.omnichannel.service.billing.refund.RefundService;
import me.nageshk.omnichannel.service.billing.wallet.WalletQueryService;
import me.nageshk.omnichannel.service.billing.wallet.WalletViews.EntryPage;
import me.nageshk.omnichannel.service.billing.wallet.WalletViews.UsageDay;
import me.nageshk.omnichannel.service.billing.wallet.WalletViews.WalletView;
import me.nageshk.omnichannel.service.billing.refund.RefundService.RefundView;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.SubscriptionView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A customer's own plan and usage, for whoever in the company may see billing. */
@RestController
@RequestMapping("/api/my/billing")
public class MyBillingController {

    private final SubscriptionService service;
    private final InvoiceService invoices;
    private final CreditNoteService creditNotes;
    private final RefundService refunds;
    private final BillingDocumentFiles files;
    private final BillingProfileService profiles;
    private final WalletQueryService wallet;

    public MyBillingController(SubscriptionService service, InvoiceService invoices, CreditNoteService creditNotes,
            RefundService refunds, BillingDocumentFiles files, BillingProfileService profiles, WalletQueryService wallet) {
        this.wallet = wallet;
        this.profiles = profiles;
        this.service = service;
        this.invoices = invoices;
        this.creditNotes = creditNotes;
        this.refunds = refunds;
        this.files = files;
    }

    @GetMapping("/subscription")
    @PreAuthorize("hasAuthority('billing:view')")
    public SubscriptionView subscription(@AuthenticationPrincipal AuthenticatedUser me) {
        return service.view(customerOf(me));
    }

    /** The states and union territories by GST code, for the profile form. */
    @GetMapping("/states")
    @PreAuthorize("hasAuthority('billing:view')")
    public List<StateView> states() {
        return IndianStates.all().stream().map(st -> new StateView(st.code(), st.name())).toList();
    }

    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('billing:view')")
    public ProfileView profile(@AuthenticationPrincipal AuthenticatedUser me) {
        return profiles.get(customerOf(me));
    }

    /** Allowed while read-only: a customer must be able to put its details right before it renews. */
    @PutMapping("/profile")
    @PreAuthorize("hasAuthority('billing:request')")
    @ReadOnlySafe
    public ProfileView putProfile(@AuthenticationPrincipal AuthenticatedUser me, @RequestBody ProfileRequest req) {
        return profiles.putOwn(customerOf(me), req, me.userId());
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasAuthority('billing:view')")
    public List<InvoiceView> invoices(@AuthenticationPrincipal AuthenticatedUser me) {
        return invoices.list(customerOf(me));
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasAuthority('billing:view')")
    public InvoiceView invoice(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id) {
        return invoices.getFor(customerOf(me), id);
    }

    @GetMapping("/credit-notes")
    @PreAuthorize("hasAuthority('billing:view')")
    public List<CreditNoteView> creditNotes(@AuthenticationPrincipal AuthenticatedUser me) {
        return creditNotes.list(customerOf(me));
    }

    @GetMapping("/credit-notes/{id}")
    @PreAuthorize("hasAuthority('billing:view')")
    public CreditNoteView creditNote(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id) {
        return creditNotes.getFor(customerOf(me), id);
    }

    @GetMapping("/invoices/{id}/pdf")
    @PreAuthorize("hasAuthority('billing:view')")
    public ResponseEntity<byte[]> invoicePdf(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id) {
        Invoice inv = invoices.requireFor(customerOf(me), id);
        return InvoiceController.download(inv.getNumber(), files.pdfOf(inv));
    }

    @GetMapping("/credit-notes/{id}/pdf")
    @PreAuthorize("hasAuthority('billing:view')")
    public ResponseEntity<byte[]> creditNotePdf(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id) {
        CreditNote note = creditNotes.requireFor(customerOf(me), id);
        return InvoiceController.download(note.getNumber(), files.pdfOf(note));
    }

    @GetMapping("/wallet")
    @PreAuthorize("hasAuthority('billing:view')")
    public WalletView wallet(@AuthenticationPrincipal AuthenticatedUser me) {
        return wallet.view(customerOf(me));
    }

    @GetMapping("/wallet/entries")
    @PreAuthorize("hasAuthority('billing:view')")
    public EntryPage walletEntries(@AuthenticationPrincipal AuthenticatedUser me, @RequestParam(defaultValue = "0") int page) {
        return wallet.entries(customerOf(me), page, false);
    }

    @GetMapping("/wallet/usage")
    @PreAuthorize("hasAuthority('billing:view')")
    public List<UsageDay> walletUsage(@AuthenticationPrincipal AuthenticatedUser me, @RequestParam(defaultValue = "30") int days) {
        return wallet.usage(customerOf(me), days);
    }

    @GetMapping("/refunds")
    @PreAuthorize("hasAuthority('billing:view')")
    public List<RefundView> refunds(@AuthenticationPrincipal AuthenticatedUser me) {
        return refunds.listRecorded(customerOf(me));
    }

    private static String customerOf(AuthenticatedUser me) {
        if (me.customerId() == null) throw new NotFoundException("no customer");
        return me.customerId();
    }
}
