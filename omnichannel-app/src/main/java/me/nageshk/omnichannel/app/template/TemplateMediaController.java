package me.nageshk.omnichannel.app.template;

import java.nio.charset.StandardCharsets;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.template.MediaView;
import me.nageshk.omnichannel.service.template.TemplateMediaService;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** A brand's template media files: {@code template:manage} to upload, {@code template:view} to download. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/template-media")
public class TemplateMediaController {

    private final TemplateMediaService service;

    public TemplateMediaController(TemplateMediaService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'template:manage')")
    public MediaView upload(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestPart(value = "file", required = false) MultipartFile file) {
        return service.upload(me, workspaceId, file);
    }

    @GetMapping("/{mediaId}")
    @PreAuthorize("@access.has(#workspaceId, 'template:view')")
    public ResponseEntity<Resource> download(@PathVariable String workspaceId, @PathVariable String mediaId) {
        TemplateMediaService.MediaFile f = service.open(workspaceId, mediaId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, f.mimeType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentLength(f.size())
                .body(f.resource());
    }
}
