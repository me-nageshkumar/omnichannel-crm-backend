package me.nageshk.omnichannel.crm.company;

import java.util.List;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.common.CrmPage;
import me.nageshk.omnichannel.crm.common.Ref;
import me.nageshk.omnichannel.crm.company.dto.CompanyRequest;
import me.nageshk.omnichannel.crm.company.dto.CompanySearchRequest;
import me.nageshk.omnichannel.crm.company.dto.CompanySummaryView;
import me.nageshk.omnichannel.crm.company.dto.CompanyView;
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
@RequestMapping("/api/workspaces/{workspaceId}/crm/companies")
public class CrmCompanyController {

    private static final String VIEW = "@access.has(#workspaceId, 'crm:view')";
    private static final String EDIT = VIEW + " and @access.has(#workspaceId, 'crm:edit')";
    private static final String DELETE = EDIT + " and @access.has(#workspaceId, 'crm:delete')";

    private final CrmAccess crmAccess;
    private final CrmCompanyService companies;

    CrmCompanyController(CrmAccess crmAccess, CrmCompanyService companies) {
        this.crmAccess = crmAccess;
        this.companies = companies;
    }

    @PostMapping("/search")
    @PreAuthorize(VIEW)
    public CrmPage<CompanyView> search(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @RequestBody(required = false) CompanySearchRequest request) {
        CompanySearchRequest r = request == null ? new CompanySearchRequest(null, null, null, null, null, null, null)
                : request;
        return companies.search(crmAccess.resolve(me, workspaceId), r);
    }

    @GetMapping("/typeahead")
    @PreAuthorize(VIEW)
    public List<Ref> typeahead(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestParam(required = false) String q) {
        return companies.typeahead(crmAccess.resolve(me, workspaceId), q);
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public CompanyView get(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return companies.get(crmAccess.resolve(me, workspaceId), id);
    }

    @GetMapping("/{id}/summary")
    @PreAuthorize(VIEW)
    public CompanySummaryView summary(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @PathVariable String id) {
        return companies.summary(crmAccess.resolve(me, workspaceId), id);
    }

    @PostMapping
    @PreAuthorize(EDIT)
    public CompanyView create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody CompanyRequest request) {
        return companies.create(crmAccess.resolve(me, workspaceId), request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(EDIT)
    public CompanyView update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody CompanyRequest request) {
        return companies.update(crmAccess.resolve(me, workspaceId), id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(DELETE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        companies.delete(crmAccess.resolve(me, workspaceId), id);
    }
}
