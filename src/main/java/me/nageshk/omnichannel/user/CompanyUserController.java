package me.nageshk.omnichannel.user;

import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.access.AssignmentPolicy;
import me.nageshk.omnichannel.access.Authority;
import me.nageshk.omnichannel.api.ForbiddenException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deactivate or reactivate any login of the caller's own customer. A user outside that customer
 * (platform staff callers included, who have none) answers 404; then {@code company-user:manage} and
 * the whole-login rule of {@link AssignmentPolicy#canDeactivateLogin} apply, else 403. Checked in
 * that order in code rather than with {@code @PreAuthorize}, so other tenants' ids are never confirmed.
 */
@RestController
@RequestMapping("/api/users/{userId}")
public class CompanyUserController {

    private final UserAdminService admin;
    private final AccessService access;

    public CompanyUserController(UserAdminService admin, AccessService access) {
        this.admin = admin;
        this.access = access;
    }

    @PostMapping("/deactivate")
    public UserSummary deactivate(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String userId) {
        return setActive(me, userId, false);
    }

    @PostMapping("/reactivate")
    public UserSummary reactivate(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String userId) {
        return setActive(me, userId, true);
    }

    private UserSummary setActive(AuthenticatedUser me, String userId, boolean active) {
        User target = admin.requireInCustomer(me.customerId(), userId);
        if (!me.hasAuthority(Authority.COMPANY_USER_MANAGE.code())) {
            throw new ForbiddenException("you may not " + (active ? "reactivate" : "deactivate") + " users");
        }
        return admin.summary(admin.setActive(access.actorOf(me), target, active));
    }
}
