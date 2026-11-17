package me.nageshk.omnichannel.app.billing;

import java.util.List;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.billing.SubscriptionService;
import me.nageshk.omnichannel.service.billing.document.CreditNoteService;
import me.nageshk.omnichannel.service.billing.document.DocumentViews.CreditNoteView;
import me.nageshk.omnichannel.service.billing.document.DocumentViews.InvoiceView;
import me.nageshk.omnichannel.service.billing.document.InvoiceService;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.SubscriptionView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A customer's own plan and usage, for whoever in the company may see billing. */
@RestController
@RequestMapping("/api/my/billing")
public class MyBillingController {

    private final SubscriptionService service;
    private final InvoiceService invoices;
    private final CreditNoteService creditNotes;

    public MyBillingController(SubscriptionService service, InvoiceService invoices, CreditNoteService creditNotes) {
        this.service = service;
        this.invoices = invoices;
        this.creditNotes = creditNotes;
    }

    @GetMapping("/subscription")
    @PreAuthorize("hasAuthority('billing:view')")
    public SubscriptionView subscription(@AuthenticationPrincipal AuthenticatedUser me) {
        return service.view(customerOf(me));
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

    private static String customerOf(AuthenticatedUser me) {
        if (me.customerId() == null) throw new NotFoundException("no customer");
        return me.customerId();
    }
}
