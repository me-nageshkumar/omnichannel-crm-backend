package me.nageshk.omnichannel.phone;

import java.util.List;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.enablement.Enablement;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * One brand's numbers: {@code phone-number:view} to list, {@code phone-number:manage} for the rest.
 * The caller acts at the level of the role it holds {@code phone-number:manage} through.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/phone-numbers")
public class BrandPhoneNumberController {

    public record AddRequest(String number, String label, String connectionType) {}

    public record LabelRequest(String label) {}

    private static final String MANAGE = "phone-number:manage";

    private final PhoneNumberService service;
    private final Enablement enablement;

    public BrandPhoneNumberController(PhoneNumberService service, Enablement enablement) {
        this.service = service;
        this.enablement = enablement;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:view')")
    public List<PhoneNumberView> list(@PathVariable String workspaceId) {
        return service.views(service.listForWorkspace(workspaceId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:manage')")
    public PhoneNumberView add(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody AddRequest req) {
        return service.view(service.create(me.customerId(), workspaceId, req.number(), req.label(), req.connectionType(), me));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:manage')")
    public PhoneNumberView relabel(@PathVariable String workspaceId, @PathVariable String id,
            @RequestBody LabelRequest req) {
        return service.view(service.relabel(service.requireInWorkspace(workspaceId, id), req.label()));
    }

    /** A number disabled above the caller's level cannot be deleted (and re-added) to get around the disable. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:manage')")
    public void delete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        PhoneNumber n = service.requireInWorkspace(workspaceId, id);
        enablement.checkEnable(n.getDisabled(), enablement.levelOf(me, MANAGE, workspaceId));
        service.delete(n);
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:manage')")
    public PhoneNumberView disable(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return setEnabled(me, workspaceId, id, false);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:manage')")
    public PhoneNumberView enable(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return setEnabled(me, workspaceId, id, true);
    }

    @PostMapping("/{id}/connect-code")
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:manage')")
    public PhoneNumberService.ConnectCodeView connectCode(@PathVariable String workspaceId, @PathVariable String id) {
        return service.issueConnectCode(service.requireInWorkspace(workspaceId, id));
    }

    @PostMapping("/{id}/revoke-device")
    @PreAuthorize("@access.has(#workspaceId, 'phone-number:manage')")
    public PhoneNumberView revokeDevice(@PathVariable String workspaceId, @PathVariable String id) {
        return service.view(service.revokeDevice(service.requireInWorkspace(workspaceId, id)));
    }

    private PhoneNumberView setEnabled(AuthenticatedUser me, String workspaceId, String id, boolean enabled) {
        PhoneNumber n = service.requireInWorkspace(workspaceId, id);
        return service.view(service.setEnabled(n, enabled, me, enablement.levelOf(me, MANAGE, workspaceId)));
    }
}
