package me.nageshk.omnichannel.crm.csv;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.company.dto.CompanySearchRequest;
import me.nageshk.omnichannel.crm.contact.dto.ContactSearchRequest;
import me.nageshk.omnichannel.crm.deal.dto.DealSearchRequest;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** CSV downloads of the contact, company and deal lists with the list's search body (spec §6.7). */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/crm")
public class CrmExportController {

    private static final String EXPORT = "@access.has(#workspaceId, 'crm:view') and @access.has(#workspaceId, "
            + "'crm:export')";
    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final CrmAccess crmAccess;
    private final CsvExportService exports;
    private final Clock clock;

    CrmExportController(CrmAccess crmAccess, CsvExportService exports, Clock clock) {
        this.crmAccess = crmAccess;
        this.exports = exports;
        this.clock = clock;
    }

    @PostMapping("/contacts/export")
    @PreAuthorize(EXPORT)
    public ResponseEntity<StreamingResponseBody> contacts(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @RequestBody(required = false) ContactSearchRequest request) {
        ContactSearchRequest r = request == null
                ? new ContactSearchRequest(null, null, null, null, null, null, null, null, null) : request;
        return csv("contacts", exports.contacts(crmAccess.resolve(me, workspaceId), r));
    }

    @PostMapping("/companies/export")
    @PreAuthorize(EXPORT)
    public ResponseEntity<StreamingResponseBody> companies(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @RequestBody(required = false) CompanySearchRequest request) {
        CompanySearchRequest r = request == null
                ? new CompanySearchRequest(null, null, null, null, null, null, null) : request;
        return csv("companies", exports.companies(crmAccess.resolve(me, workspaceId), r));
    }

    @PostMapping("/deals/export")
    @PreAuthorize(EXPORT)
    public ResponseEntity<StreamingResponseBody> deals(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @RequestBody(required = false) DealSearchRequest request) {
        DealSearchRequest r = request == null
                ? new DealSearchRequest(null, null, null, null, null, null, null, null, null, null, null) : request;
        return csv("deals", exports.deals(crmAccess.resolve(me, workspaceId), r));
    }

    private ResponseEntity<StreamingResponseBody> csv(String entity, CsvExportService.CsvExport export) {
        String fileName = "crm-" + entity + "-" + LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).format(STAMP)
                + ".csv";
        StreamingResponseBody body = export::writeTo;
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"").body(body);
    }
}
