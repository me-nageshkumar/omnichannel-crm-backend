package me.nageshk.omnichannel.crm.csv;

import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.csv.dto.ImportResult;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** CSV uploads of companies and contacts (spec §6.7). */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/crm")
public class CrmImportController {

    private static final String EDIT = "@access.has(#workspaceId, 'crm:view') and @access.has(#workspaceId, "
            + "'crm:edit')";

    private final CrmAccess crmAccess;
    private final CsvImportService imports;

    CrmImportController(CrmAccess crmAccess, CsvImportService imports) {
        this.crmAccess = crmAccess;
        this.imports = imports;
    }

    @PostMapping("/companies/import")
    @PreAuthorize(EDIT)
    public ImportResult companies(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestPart("file") MultipartFile file) {
        return imports.importCompanies(crmAccess.resolve(me, workspaceId), file);
    }

    @PostMapping("/contacts/import")
    @PreAuthorize(EDIT)
    public ImportResult contacts(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestPart("file") MultipartFile file, @RequestParam String pipelineId,
            @RequestParam String phoneNumberId) {
        return imports.importContacts(crmAccess.resolve(me, workspaceId), file, pipelineId, phoneNumberId);
    }
}
