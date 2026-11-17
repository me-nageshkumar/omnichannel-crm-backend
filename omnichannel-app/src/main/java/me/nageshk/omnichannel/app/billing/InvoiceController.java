package me.nageshk.omnichannel.app.billing;

import java.util.List;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.service.billing.document.BillingDocumentFiles;
import me.nageshk.omnichannel.service.billing.document.DocumentViews.InvoiceView;
import me.nageshk.omnichannel.service.billing.document.InvoiceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final BillingDocumentFiles files;

    public InvoiceController(InvoiceService service, BillingDocumentFiles files) {
        this.service = service;
        this.files = files;
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

    @GetMapping("/invoices/{id}/pdf")
    @PreAuthorize(OrderController.VIEW)
    public ResponseEntity<byte[]> pdf(@PathVariable String id) {
        Invoice inv = service.require(id);
        return download(inv.getNumber(), files.pdfOf(inv));
    }

    /** A PDF answered as a file to save, named after the document's number. */
    static ResponseEntity<byte[]> download(String number, byte[] pdf) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + BillingDocumentFiles.fileName(number) + "\"")
                .body(pdf);
    }
}
