package me.nageshk.omnichannel.app.billing;

import java.util.List;
import me.nageshk.omnichannel.service.billing.document.CreditNoteService;
import me.nageshk.omnichannel.service.billing.document.DocumentViews.CreditNoteView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform staff: the credit notes issued to customers. */
@RestController
@RequestMapping("/api/billing")
public class CreditNoteController {

    private final CreditNoteService service;

    public CreditNoteController(CreditNoteService service) {
        this.service = service;
    }

    @GetMapping("/customers/{customerId}/credit-notes")
    @PreAuthorize(OrderController.VIEW)
    public List<CreditNoteView> list(@PathVariable String customerId) {
        return service.list(customerId);
    }

    @GetMapping("/credit-notes/{id}")
    @PreAuthorize(OrderController.VIEW)
    public CreditNoteView get(@PathVariable String id) {
        return service.get(id);
    }
}
