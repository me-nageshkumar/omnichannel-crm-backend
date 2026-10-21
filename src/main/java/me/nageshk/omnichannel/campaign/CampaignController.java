package me.nageshk.omnichannel.campaign;

import me.nageshk.omnichannel.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** One brand's campaigns. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/campaigns")
class CampaignController {

    private final CampaignService service;
    private final CampaignViews views;

    CampaignController(CampaignService service, CampaignViews views) {
        this.service = service;
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

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has(#workspaceId, 'campaign:manage')")
    void delete(@PathVariable String workspaceId, @PathVariable String id) {
        service.delete(workspaceId, id);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'campaign:view')")
    CampaignView get(@PathVariable String workspaceId, @PathVariable String id) {
        return views.detail(service.require(workspaceId, id));
    }
}
