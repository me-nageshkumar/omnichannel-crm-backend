package me.nageshk.omnichannel.service.billing.document;

import com.mongodb.client.gridfs.model.GridFSFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;
import me.nageshk.omnichannel.db.billing.CreditNote;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.security.entitlement.BillingSettingsStore;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Component;

/**
 * The PDF file of an invoice or credit note. It is drawn the first time someone asks for it and kept in GridFS,
 * so every later download is the very same file.
 */
@Component
public class BillingDocumentFiles {

    private static final Logger log = LoggerFactory.getLogger(BillingDocumentFiles.class);

    private final GridFsTemplate files;
    private final MongoTemplate mongo;
    private final BillingSettingsStore settings;

    public BillingDocumentFiles(@Qualifier("billingDocumentsGridFs") GridFsTemplate files, MongoTemplate mongo,
            BillingSettingsStore settings) {
        this.files = files;
        this.mongo = mongo;
        this.settings = settings;
    }

    /** The name a download is saved under: the number with its slashes as dashes. */
    public static String fileName(String number) {
        return number.replace('/', '-') + ".pdf";
    }

    public byte[] pdfOf(Invoice inv) {
        return pdf(Invoice.class, inv.getId(), inv.getPdfFileId(), fileName(inv.getNumber()),
                () -> DocumentPdf.invoice(inv, settings.zone()));
    }

    public byte[] pdfOf(CreditNote note) {
        return pdf(CreditNote.class, note.getId(), note.getPdfFileId(), fileName(note.getNumber()),
                () -> DocumentPdf.creditNote(note, settings.zone()));
    }

    private byte[] pdf(Class<?> type, String id, String fileId, String name, Supplier<byte[]> draw) {
        byte[] stored = read(fileId);
        if (stored != null) return stored;
        byte[] drawn = draw.get();
        String mine = files.store(new ByteArrayInputStream(drawn), name, "application/pdf").toHexString();
        // only the first to finish becomes the document's file; a file that went missing is replaced
        long won = mongo.updateFirst(new Query(Criteria.where("_id").is(id).and("pdfFileId").is(fileId)),
                new Update().set("pdfFileId", mine), type).getModifiedCount();
        if (won == 1) return drawn;
        files.delete(new Query(Criteria.where("_id").is(new ObjectId(mine))));
        org.bson.Document now = mongo.getCollection(mongo.getCollectionName(type))
                .find(new org.bson.Document("_id", new ObjectId(id))).first();
        byte[] winner = read(now == null ? null : now.getString("pdfFileId"));
        return winner != null ? winner : drawn;
    }

    /** The stored file's bytes, or null when there is none (never drawn, or the file is gone). */
    private byte[] read(String fileId) {
        if (fileId == null) return null;
        try {
            GridFSFile file = files.findOne(new Query(Criteria.where("_id").is(new ObjectId(fileId))));
            if (file == null) return null;
            try (InputStream in = files.getResource(file).getInputStream()) {
                return in.readAllBytes();
            }
        } catch (IOException | RuntimeException e) {
            log.warn("could not read billing document file {}; it will be drawn again", fileId, e);
            return null;
        }
    }
}
