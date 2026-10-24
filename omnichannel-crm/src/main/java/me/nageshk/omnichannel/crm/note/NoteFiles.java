package me.nageshk.omnichannel.crm.note;

import java.io.IOException;
import java.io.InputStream;
import me.nageshk.omnichannel.crm.common.TargetType;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Component;

/**
 * The note attachment files in GridFS, tagged with their brand, note and target so a deleted note, deal or company
 * takes its files with it (Ruling B6).
 */
@Component
public class NoteFiles {

    private final GridFsTemplate gridFs;

    NoteFiles(@Qualifier("crmNoteFilesGridFs") GridFsTemplate gridFs) {
        this.gridFs = gridFs;
    }

    String store(String ws, CrmNote note, String fileName, String contentType, InputStream in) throws IOException {
        Document meta = new Document("workspaceId", ws).append("noteId", note.getId())
                .append("targetType", note.getTarget().type().name()).append("targetId", note.getTarget().id());
        return gridFs.store(in, fileName, contentType, meta).toHexString();
    }

    GridFsResource open(String ws, String fileId) {
        var file = gridFs.findOne(byId(ws, fileId));
        return file == null ? null : gridFs.getResource(file);
    }

    void delete(String ws, String fileId) {
        gridFs.delete(byId(ws, fileId));
    }

    void deleteForNote(String ws, String noteId) {
        gridFs.delete(new Query(Criteria.where("metadata.workspaceId").is(ws).and("metadata.noteId").is(noteId)));
    }

    /** Every attachment of every note on the record. */
    public void deleteForTarget(String ws, TargetType type, String id) {
        gridFs.delete(new Query(Criteria.where("metadata.workspaceId").is(ws).and("metadata.targetType").is(type.name())
                .and("metadata.targetId").is(id)));
    }

    private static Query byId(String ws, String fileId) {
        Object id = ObjectId.isValid(fileId) ? new ObjectId(fileId) : fileId;
        return new Query(Criteria.where("_id").is(id).and("metadata.workspaceId").is(ws));
    }
}
