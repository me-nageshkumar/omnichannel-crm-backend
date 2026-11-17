package me.nageshk.omnichannel.app.billing;

import java.util.List;
import me.nageshk.omnichannel.service.billing.document.DocumentViews.InvoiceView;
import me.nageshk.omnichannel.service.billing.document.InvoiceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform staff: the invoices issued to customers. */
@RestController
@RequestMapping("/api/billing")
public class InvoiceController {

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @GetMapping("/customers/{customerId}/invoices")
    @PreAuthorize(OrderController.VIEW)
    public List<InvoiceView> list(@PathVariable String customerId) {
        return service.list(customerId);
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize(OrderController.VIEW)
    public InvoiceView get(@PathVariable String id) {
        return service.get(id);
    }
}
