package me.nageshk.omnichannel.meta.template;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.meta.api.CloudTemplateInfo;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A brand's WhatsApp templates (Meta's, imported): the pickers read them, admins sync them and set their fills. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/meta/templates")
class CloudTemplateController {

    record SyncRequest(String connectionId) {}

    record FillsRequest(Map<String, CloudTemplateInfo.Fill> fills) {}

    private final CloudTemplateService service;

    CloudTemplateController(CloudTemplateService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'inbox:send') or @access.has(#workspaceId, 'phone-number:view')")
    List<CloudTemplateInfo> list(@PathVariable String workspaceId,
            @RequestParam(required = false) String phoneNumberId,
            @RequestParam(defaultValue = "false") boolean sendable) {
        return service.list(workspaceId, phoneNumberId, sendable);
    }

    @PostMapping("/sync")
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:manage')")
    CloudTemplateService.SyncResult sync(@PathVariable String workspaceId,
            @RequestBody(required = false) SyncRequest body) {
        return service.sync(workspaceId, body == null ? null : body.connectionId());
    }

    @PutMapping("/{id}/fills")
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:manage')")
    CloudTemplateInfo fills(@PathVariable String workspaceId, @PathVariable String id, @RequestBody FillsRequest body) {
        return service.saveFills(workspaceId, id, body == null ? null : body.fills());
    }
}
