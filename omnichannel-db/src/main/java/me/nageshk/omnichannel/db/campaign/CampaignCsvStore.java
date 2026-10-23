package me.nageshk.omnichannel.db.campaign;

import com.mongodb.client.gridfs.model.GridFSFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Component;

/** The uploaded CSV files of campaign audiences, in the GridFS bucket {@code campaignCsv}. */
@Component
public class CampaignCsvStore {

    private final GridFsTemplate gridFs;

    CampaignCsvStore(@Qualifier("campaignCsvGridFs") GridFsTemplate gridFs) {
        this.gridFs = gridFs;
    }

    /** Stores the bytes and returns the file's id. */
    public String store(String workspaceId, String fileName, byte[] bytes) {
        return gridFs.store(new ByteArrayInputStream(bytes), fileName, "text/csv",
                new Document("workspaceId", workspaceId)).toHexString();
    }

    public byte[] read(String fileId) {
        GridFSFile f = gridFs.findOne(byId(fileId));
        if (f == null) throw new IllegalStateException("campaign csv " + fileId + " is missing");
        try {
            return gridFs.getResource(f).getContentAsByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(String fileId) {
        if (fileId != null && ObjectId.isValid(fileId)) gridFs.delete(byId(fileId));
    }

    private static Query byId(String fileId) {
        return new Query(Criteria.where("_id").is(new ObjectId(fileId)));
    }
}
