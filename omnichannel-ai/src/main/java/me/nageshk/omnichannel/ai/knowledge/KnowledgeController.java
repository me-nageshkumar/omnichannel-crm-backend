package me.nageshk.omnichannel.ai.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeViews.BaseView;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeViews.NoteIn;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeViews.SaveBase;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeViews.SourceChange;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeViews.SourceView;
import me.nageshk.omnichannel.ai.knowledge.KnowledgeViews.WebPageIn;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** A brand's knowledge bases and their sources (spec 2026-11-18 §6). */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/ai/knowledge-bases")
class KnowledgeController {

    private final KnowledgeService service;

    KnowledgeController(KnowledgeService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'ai:view')")
    List<BaseView> list(@PathVariable String workspaceId) {
        return service.list(workspaceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'ai:manage')")
    BaseView create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody SaveBase body) {
        return service.create(workspaceId, me.customerId(), me.userId(), body);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'ai:view')")
    BaseView get(@PathVariable String workspaceId, @PathVariable String id) {
        return service.get(workspaceId, id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'ai:manage')")
    BaseView replace(@PathVariable String workspaceId, @PathVariable String id, @RequestBody SaveBase body) {
        return service.replace(workspaceId, id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has(#workspaceId, 'ai:manage')")
    void delete(@PathVariable String workspaceId, @PathVariable String id) {
        service.delete(workspaceId, id);
    }

    @GetMapping("/{id}/sources")
    @PreAuthorize("@access.has(#workspaceId, 'ai:view')")
    List<SourceView> sources(@PathVariable String workspaceId, @PathVariable String id) {
        return service.sources(workspaceId, id);
    }

    @PostMapping(value = "/{id}/sources/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'ai:manage')")
    SourceView addFile(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestPart("file") MultipartFile file) {
        return service.addFile(workspaceId, id, me.userId(), file);
    }

    @PostMapping("/{id}/sources/web-page")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'ai:manage')")
    SourceView addWebPage(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody WebPageIn body) {
        return service.addWebPage(workspaceId, id, me.userId(), body);
    }

    @PostMapping("/{id}/sources/note")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'ai:manage')")
    SourceView addNote(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody NoteIn body) {
        return service.addNote(workspaceId, id, me.userId(), body);
    }

    @PutMapping("/{id}/sources/{sourceId}")
    @PreAuthorize("@access.has(#workspaceId, 'ai:manage')")
    SourceView change(@PathVariable String workspaceId, @PathVariable String id, @PathVariable String sourceId,
            @RequestBody SourceChange body) {
        return service.change(workspaceId, id, sourceId, body);
    }

    @DeleteMapping("/{id}/sources/{sourceId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("@access.has(#workspaceId, 'ai:manage')")
    SourceView remove(@PathVariable String workspaceId, @PathVariable String id, @PathVariable String sourceId) {
        return service.remove(workspaceId, id, sourceId);
    }

    @GetMapping("/{id}/sources/{sourceId}/content")
    @PreAuthorize("@access.has(#workspaceId, 'ai:view')")
    ResponseEntity<InputStreamResource> content(@PathVariable String workspaceId, @PathVariable String id,
            @PathVariable String sourceId) throws IOException {
        KnowledgeService.Content c = service.content(workspaceId, id, sourceId);
        KnowledgeSource s = c.source();
        HttpHeaders h = new HttpHeaders();
        String name = s.getFileName() != null ? s.getFileName() : s.getTitle() + ".md";
        h.setContentDisposition(ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build());
        h.set("X-Content-Type-Options", "nosniff");
        h.setContentType(MediaType.parseMediaType(s.getContentType() == null ? "application/octet-stream" : s.getContentType()));
        h.setContentLength(c.resource().contentLength());
        return new ResponseEntity<>(new InputStreamResource(c.resource().getInputStream()), h, HttpStatus.OK);
    }
}
