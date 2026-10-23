package me.nageshk.omnichannel.crm.field;

import java.util.List;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.field.dto.FieldDefinitionRequest;
import me.nageshk.omnichannel.crm.field.dto.FieldDefinitionView;
import me.nageshk.omnichannel.crm.field.dto.ReorderRequest;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/crm/fields")
public class CrmFieldController {

    private static final String VIEW = "@access.has(#workspaceId, 'crm:view')";
    private static final String WRITE = VIEW + " and @access.has(#workspaceId, 'crm:settings')";

    private final CrmAccess crmAccess;
    private final FieldDefinitionService service;

    CrmFieldController(CrmAccess crmAccess, FieldDefinitionService service) {
        this.crmAccess = crmAccess;
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public List<FieldDefinitionView> list(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestParam String entityType, @RequestParam(defaultValue = "false") boolean includeInactive) {
        TargetType entity;
        try {
            entity = TargetType.valueOf(entityType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("entityType", "must be CONTACT, COMPANY or DEAL");
        }
        return service.list(crmAccess.resolve(me, workspaceId), entity, includeInactive).stream()
                .map(FieldDefinitionView::of).toList();
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public FieldDefinitionView create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody FieldDefinitionRequest request) {
        return FieldDefinitionView.of(service.create(crmAccess.resolve(me, workspaceId), request));
    }

    @PutMapping("/reorder")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorder(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody ReorderRequest request) {
        service.reorder(crmAccess.resolve(me, workspaceId), request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    public FieldDefinitionView update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody FieldDefinitionRequest request) {
        return FieldDefinitionView.of(service.update(crmAccess.resolve(me, workspaceId), id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        service.delete(crmAccess.resolve(me, workspaceId), id);
    }
}
