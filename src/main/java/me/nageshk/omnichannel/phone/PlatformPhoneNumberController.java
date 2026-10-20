package me.nageshk.omnichannel.phone;

import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.enablement.EnableLevel;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Every number on the platform, for platform staff: search, enable/disable at PLATFORM level, delete. */
@RestController
@RequestMapping("/api/phone-numbers")
public class PlatformPhoneNumberController {

    private final PhoneNumberService service;

    public PlatformPhoneNumberController(PhoneNumberService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('phone-number:view-all')")
    public PhoneNumberView.Page search(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page) {
        return service.search(q, status, page);
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('phone-number:manage-all')")
    public PhoneNumberView disable(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id) {
        return service.view(service.setEnabled(service.require(id), false, me, EnableLevel.PLATFORM));
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('phone-number:manage-all')")
    public PhoneNumberView enable(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id) {
        return service.view(service.setEnabled(service.require(id), true, me, EnableLevel.PLATFORM));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('phone-number:manage-all')")
    public void delete(@PathVariable String id) {
        service.delete(service.require(id));
    }
}
