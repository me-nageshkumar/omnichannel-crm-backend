package me.nageshk.omnichannel.contact;

import java.io.IOException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** CSV import of one brand's contacts: {@code contact:import}. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/contacts/import")
public class ContactImportController {

    private final ContactImportService service;

    public ContactImportController(ContactImportService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("@access.has(#workspaceId, 'contact:import')")
    public ContactImportService.Result upload(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @RequestPart("file") MultipartFile file) throws IOException {
        return service.run(workspaceId, me.customerId(), file.getBytes());
    }
}
