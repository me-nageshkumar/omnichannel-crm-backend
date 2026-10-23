package me.nageshk.omnichannel.app.campaign;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignCounter;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.campaign.CampaignLifecycleService;
import me.nageshk.omnichannel.service.campaign.CampaignQueryService;
import me.nageshk.omnichannel.service.campaign.CampaignRequest;
import me.nageshk.omnichannel.service.campaign.CampaignService;
import me.nageshk.omnichannel.service.campaign.CampaignView;
import me.nageshk.omnichannel.service.campaign.CampaignViews;
import me.nageshk.omnichannel.service.campaign.CsvPreview;
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

/** One brand's campaigns. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/campaigns")
class CampaignController {

    private final CampaignService service;
    private final CampaignLifecycleService lifecycle;
    private final CampaignViews views;
    private final CampaignQueryService queries;
    private final CampaignCounter counter;

    CampaignController(CampaignService service, CampaignLifecycleService lifecycle, CampaignViews views,
            CampaignQueryService queries, CampaignCounter counter) {
        this.queries = queries;
        this.counter = counter;
        this.service = service;
        this.lifecycle = lifecycle;
        this.views = views;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'campaign:manage')")
    CampaignView create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody CampaignRequest req) {
        return views.detail(service.create(me, workspaceId, req, null));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@access.has(#workspaceId, 'campaign:manage')")
    CampaignView update(@PathVariable String workspaceId, @PathVariable String id, @RequestBody CampaignRequest req) {
        return views.detail(service.update(workspaceId, id, req, null));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'campaign:manage')")
    CampaignView createWithFile(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestPart("campaign") CampaignRequest req, @RequestPart(value = "file", required = false) MultipartFile file)
            throws IOException {
        return views.detail(service.create(me, workspaceId, req, upload(file)));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.has(#workspaceId, 'campaign:manage')")
    CampaignView updateWithFile(@PathVariable String workspaceId, @PathVariable String id,
            @RequestPart("campaign") CampaignRequest req, @RequestPart(value = "file", required = false) MultipartFile file)
            throws IOException {
        return views.detail(service.update(workspaceId, id, req, upload(file)));
    }

    @PostMapping(path = "/csv/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.has(#workspaceId, 'campaign:manage')")
    CsvPreview preview(@PathVariable String workspaceId, @RequestPart("file") MultipartFile file,
            @RequestParam(value = "templateIds", required = false) List<String> templateIds) throws IOException {
        return service.preview(workspaceId, file.getBytes(), templateIds);
    }

    @GetMapping("/templates/{templateId}/sample-csv")
    @PreAuthorize("@access.has(#workspaceId, 'campaign:view')")
    ResponseEntity<String> sampleCsv(@PathVariable String workspaceId, @PathVariable String templateId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sample.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(service.sampleCsv(workspaceId, templateId));
    }

    private static CampaignService.CsvUpload upload(MultipartFile file) throws IOException {
        return file == null ? null : new CampaignService.CsvUpload(file.getOriginalFilename(), file.getBytes());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has(#workspaceId, 'campaign:manage')")
    void delete(@PathVariable String workspaceId, @PathVariable String id) {
        service.delete(workspaceId, id);
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("@access.has(#workspaceId, 'campaign:manage')")
    CampaignView start(@PathVariable String workspaceId, @PathVariable String id) {
        return views.detail(lifecycle.start(workspaceId, id));
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("@access.has(#workspaceId, 'campaign:manage')")
    CampaignView pause(@PathVariable String workspaceId, @PathVariable String id) {
        return views.detail(lifecycle.pause(workspaceId, id));
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("@access.has(#workspaceId, 'campaign:manage')")
    CampaignView resume(@PathVariable String workspaceId, @PathVariable String id) {
        return views.detail(lifecycle.resume(workspaceId, id));
    }

    @PostMapping("/{id}/terminate")
    @PreAuthorize("@access.has(#workspaceId, 'campaign:manage')")
    CampaignView terminate(@PathVariable String workspaceId, @PathVariable String id) {
        return views.detail(lifecycle.terminate(workspaceId, id));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'campaign:view')")
    CampaignView get(@PathVariable String workspaceId, @PathVariable String id) {
        Campaign c = service.require(workspaceId, id);
        c.setCounts(counter.refresh(c.getId()));
        return views.detail(c);
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'campaign:view')")
    CampaignQueryService.CampaignPage list(@PathVariable String workspaceId,
            @RequestParam(required = false) CampaignStatus status, @RequestParam(defaultValue = "0") int page) {
        return queries.list(workspaceId, status, page);
    }

    @GetMapping("/{id}/recipients")
    @PreAuthorize("@access.has(#workspaceId, 'campaign:view')")
    CampaignQueryService.RecipientPage recipients(@PathVariable String workspaceId, @PathVariable String id,
            @RequestParam(required = false) RecipientStatus status, @RequestParam(defaultValue = "0") int page) {
        service.require(workspaceId, id);
        return queries.recipients(id, status, page);
    }
}
