package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import me.nageshk.omnichannel.db.billing.CreditNoteRepository;
import me.nageshk.omnichannel.db.billing.InvoiceRepository;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;

/** A document's PDF is drawn on the first download and is the same file ever after. */
class DocumentPdfIT extends MoneyApiTestBase {

    @Autowired MongoTemplate mongo;
    @Autowired InvoiceRepository invoices;
    @Autowired CreditNoteRepository creditNotes;

    @BeforeEach
    void noFiles() {
        mongo.remove(new Query(), "billingDocuments.files");
        mongo.remove(new Query(), "billingDocuments.chunks");
    }

    private long files() {
        return mongo.count(new Query(), "billingDocuments.files");
    }

    @Test
    void theInvoiceFileIsDrawnOnceAndKept() throws Exception {
        String id = buy(acme, request("NEW", "gold-monthly")).get("invoiceId").asString();
        assertThat(files()).isZero();

        byte[] first = GET("/api/billing/invoices/" + id + "/pdf", amToken).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"INV-2026-27-000001.pdf\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(first, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(files()).isEqualTo(1);
        String fileId = invoices.findById(id).orElseThrow().getPdfFileId();
        assertThat(fileId).isNotBlank();
        String out = System.getProperty("billing.pdf.out");
        if (out != null) Files.write(Path.of(out), first);

        // the customer's profile changes; the file does not
        PUT(profilePath(acme), amToken, profile("Acme Global Ltd", "5 New Road", "27")).andExpect(status().isOk());
        byte[] second = GET("/api/my/billing/invoices/" + id + "/pdf", adminToken).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
        assertThat(second).isEqualTo(first);
        assertThat(files()).isEqualTo(1);
        assertThat(invoices.findById(id).orElseThrow().getPdfFileId()).isEqualTo(fileId);

        // a file that was lost is drawn again
        mongo.remove(new Query(Criteria.where("_id").is(new ObjectId(fileId))), "billingDocuments.files");
        GET("/api/billing/invoices/" + id + "/pdf", amToken).andExpect(status().isOk());
        assertThat(files()).isEqualTo(1);
        assertThat(invoices.findById(id).orElseThrow().getPdfFileId()).isNotEqualTo(fileId);
    }

    @Test
    void aCreditNoteHasItsFileAndOnlyItsCustomerMayHaveIt() throws Exception {
        String invoiceId = buy(acme, request("NEW", "gold-monthly")).get("invoiceId").asString();
        on(day(2026, 12, 16));
        JsonNode upgrade = buy(acme, request("UPGRADE", "platinum-monthly"));
        String noteId = upgrade.at("/creditNoteIds/0").asString();

        GET("/api/billing/credit-notes/" + noteId + "/pdf", amToken).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"CN-2026-27-000001.pdf\""));
        GET("/api/my/billing/credit-notes/" + noteId + "/pdf", adminToken).andExpect(status().isOk());
        assertThat(files()).isEqualTo(1);
        assertThat(creditNotes.findById(noteId).orElseThrow().getPdfFileId()).isNotBlank();

        GET("/api/my/billing/credit-notes/" + noteId + "/pdf", boltToken).andExpect(status().isNotFound());
        GET("/api/my/billing/invoices/" + invoiceId + "/pdf", boltToken).andExpect(status().isNotFound());
        GET("/api/billing/invoices/" + invoiceId + "/pdf", adminToken).andExpect(status().isForbidden());
        GET("/api/billing/invoices/000000000000000000000000/pdf", amToken).andExpect(status().isNotFound());
        assertThat(files()).isEqualTo(1);
    }
}
