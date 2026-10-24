package me.nageshk.omnichannel.crm.note;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.common.CrmPage;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.note.dto.NoteRequest;
import me.nageshk.omnichannel.crm.note.dto.NoteView;
import me.nageshk.omnichannel.crm.note.dto.PinRequest;
import me.nageshk.omnichannel.crm.task.TaskService;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/crm/notes")
public class CrmNoteController {

    private static final String VIEW = "@access.has(#workspaceId, 'crm:view')";
    private static final String EDIT = VIEW + " and @access.has(#workspaceId, 'crm:edit')";

    private final CrmAccess crmAccess;
    private final NoteService notes;
    private final AttachmentService attachments;

    CrmNoteController(CrmAccess crmAccess, NoteService notes, AttachmentService attachments) {
        this.crmAccess = crmAccess;
        this.notes = notes;
        this.attachments = attachments;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public CrmPage<NoteView> list(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestParam String targetType, @RequestParam String targetId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        CrmTarget target = new CrmTarget(TaskService.parse(TargetType.class, targetType, "targetType"), targetId);
        return notes.list(crmAccess.resolve(me, workspaceId), target, page, size);
    }

    @PostMapping
    @PreAuthorize(EDIT)
    public NoteView create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody NoteRequest request) {
        return notes.create(crmAccess.resolve(me, workspaceId), request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(EDIT)
    public NoteView update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody NoteRequest request) {
        return notes.update(crmAccess.resolve(me, workspaceId), id, request);
    }

    @PostMapping("/{id}/pin")
    @PreAuthorize(EDIT)
    public NoteView pin(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody PinRequest request) {
        return notes.pin(crmAccess.resolve(me, workspaceId), id, Boolean.TRUE.equals(request.pinned()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        notes.delete(crmAccess.resolve(me, workspaceId), id);
    }

    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(EDIT)
    public NoteView upload(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestPart("file") MultipartFile file) {
        return attachments.add(crmAccess.resolve(me, workspaceId), id, file);
    }

    @DeleteMapping("/{id}/attachments/{attachmentId}")
    @PreAuthorize(EDIT)
    public NoteView removeAttachment(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @PathVariable String attachmentId) {
        return attachments.remove(crmAccess.resolve(me, workspaceId), id, attachmentId);
    }

    @GetMapping("/{id}/attachments/{attachmentId}")
    @PreAuthorize(VIEW)
    public ResponseEntity<InputStreamResource> download(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @PathVariable String id, @PathVariable String attachmentId)
            throws IOException {
        AttachmentService.Download d = attachments.open(crmAccess.resolve(me, workspaceId), id, attachmentId);
        HttpHeaders h = new HttpHeaders();
        h.setContentDisposition(ContentDisposition.attachment()
                .filename(d.attachment().fileName(), StandardCharsets.UTF_8).build());
        h.set("X-Content-Type-Options", "nosniff");
        h.setContentType(MediaType.parseMediaType(d.attachment().contentType()));
        h.setContentLength(d.attachment().size());
        return new ResponseEntity<>(new InputStreamResource(d.resource().getInputStream()), h, HttpStatus.OK);
    }
}
