package me.nageshk.omnichannel.app.page;

import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.db.page.PagesAddress;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.page.PagesAddressChecker;
import me.nageshk.omnichannel.service.page.PagesAddressViews.BrandRow;
import me.nageshk.omnichannel.service.page.PagesAddressViews.CustomerView;
import me.nageshk.omnichannel.service.page.PagesAddresses;
import me.nageshk.omnichannel.service.page.PagesAddresses.PlatformView;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The super admin's link addresses (spec 2026-11-18 link addresses §5.8): the platform's and each brand's. */
@RestController
@PreAuthorize("hasAuthority('pages-address:manage')")
public class PagesAddressController {

    /** {@code baseUrl} null removes the address. */
    record AddressRequest(String baseUrl, Long version) {
    }

    private final PagesAddresses addresses;
    private final PagesAddressChecker checker;

    public PagesAddressController(PagesAddresses addresses, PagesAddressChecker checker) {
        this.addresses = addresses;
        this.checker = checker;
    }

    @GetMapping("/api/platform/pages-address")
    public PlatformView platform() {
        return addresses.platformView();
    }

    @PutMapping("/api/platform/pages-address")
    public PlatformView savePlatform(@AuthenticationPrincipal AuthenticatedUser me, @RequestBody AddressRequest req) {
        return addresses.savePlatform(blankToNull(req.baseUrl()), req.version(), me.userId());
    }

    @GetMapping("/api/customers/{customerId}/pages-addresses")
    public CustomerView forCustomer(@PathVariable String customerId) {
        return addresses.forCustomer(customerId);
    }

    @PutMapping("/api/customers/{customerId}/workspaces/{workspaceId}/pages-address")
    public BrandRow set(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String customerId,
            @PathVariable String workspaceId, @RequestBody AddressRequest req) {
        String url = blankToNull(req.baseUrl());
        if (url == null) {
            addresses.remove(customerId, workspaceId, req.version(), me.userId());
        } else {
            addresses.set(customerId, workspaceId, url, req.version(), me.userId());
        }
        return addresses.row(customerId, workspaceId);
    }

    @PostMapping("/api/customers/{customerId}/workspaces/{workspaceId}/pages-address/check")
    public BrandRow check(@PathVariable String customerId, @PathVariable String workspaceId) {
        BrandRow before = addresses.row(customerId, workspaceId);
        if (before.address() == null) {
            throw new CodedException(HttpStatus.NOT_FOUND, "NO_PAGES_ADDRESS", null, "This brand has no link address");
        }
        PagesAddress checked = checker.check(workspaceId);
        return checked == null ? before : addresses.row(customerId, workspaceId);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
