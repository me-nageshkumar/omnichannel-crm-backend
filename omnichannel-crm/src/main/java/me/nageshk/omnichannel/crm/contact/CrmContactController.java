package me.nageshk.omnichannel.crm.contact;

import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.common.CrmPage;
import me.nageshk.omnichannel.crm.contact.dto.CaptureRequest;
import me.nageshk.omnichannel.crm.contact.dto.CaptureResult;
import me.nageshk.omnichannel.crm.contact.dto.CommunicationView;
import me.nageshk.omnichannel.crm.contact.dto.ContactSearchRequest;
import me.nageshk.omnichannel.crm.contact.dto.ContactUpdateRequest;
import me.nageshk.omnichannel.crm.contact.dto.CreateRequest;
import me.nageshk.omnichannel.crm.contact.dto.CrmContactView;
import me.nageshk.omnichannel.crm.contact.dto.InboxSummaryView;
import me.nageshk.omnichannel.crm.contact.dto.MoveChannelRequest;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/crm/contacts")
public class CrmContactController {

    private static final String VIEW = "@access.has(#workspaceId, 'crm:view')";
    private static final String EDIT = VIEW + " and @access.has(#workspaceId, 'crm:edit')";
    private static final String SETTINGS = VIEW + " and @access.has(#workspaceId, 'crm:settings')";

    private final CrmAccess crmAccess;
    private final CrmContactService contacts;
    private final CaptureService capture;
    private final ChannelService channels;
    private final InboxSummaryService inbox;

    CrmContactController(CrmAccess crmAccess, CrmContactService contacts, CaptureService capture,
            ChannelService channels, InboxSummaryService inbox) {
        this.inbox = inbox;
        this.crmAccess = crmAccess;
        this.contacts = contacts;
        this.capture = capture;
        this.channels = channels;
    }

    @PostMapping("/search")
    @PreAuthorize(VIEW)
    public CrmPage<CrmContactView> search(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @RequestBody(required = false) ContactSearchRequest request) {
        ContactSearchRequest r = request == null
                ? new ContactSearchRequest(null, null, null, null, null, null, null, null, null) : request;
        return contacts.search(crmAccess.resolve(me, workspaceId), r);
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public CrmContactView get(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return contacts.get(crmAccess.resolve(me, workspaceId), id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(EDIT)
    public CrmContactView update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody ContactUpdateRequest request) {
        return contacts.update(crmAccess.resolve(me, workspaceId), id, request);
    }

    @PostMapping("/capture")
    @PreAuthorize(EDIT)
    public CaptureResult capture(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody CaptureRequest request) {
        return capture.capture(crmAccess.resolve(me, workspaceId), request);
    }

    @PostMapping("/create")
    @PreAuthorize(EDIT)
    public CaptureResult create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody CreateRequest request) {
        return capture.create(crmAccess.resolve(me, workspaceId), request);
    }

    @GetMapping("/by-chat/{chatId}/summary")
    @PreAuthorize(VIEW)
    public InboxSummaryView summary(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String chatId) {
        return inbox.summary(crmAccess.resolve(me, workspaceId), chatId);
    }

    @GetMapping("/{id}/communication")
    @PreAuthorize(VIEW)
    public CommunicationView communication(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @PathVariable String id,
            @RequestParam(required = false) String phoneNumberId) {
        return channels.communication(crmAccess.resolve(me, workspaceId), id, phoneNumberId);
    }

    @PostMapping("/{id}/move-channel")
    @PreAuthorize(SETTINGS)
    public CrmContactView moveChannel(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @PathVariable String id, @RequestBody MoveChannelRequest request) {
        return channels.move(crmAccess.resolve(me, workspaceId), id, request);
    }
}
