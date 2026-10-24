package me.nageshk.omnichannel.crm.note.dto;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.Ref;

public record NoteView(String id, CrmTarget target, String body, boolean pinned, List<Attachment> attachments,
        Ref author, Instant creationTime, Instant lastUpdateTime, Long version) {

    public record Attachment(String id, String fileName, String contentType, long size) {}
}
