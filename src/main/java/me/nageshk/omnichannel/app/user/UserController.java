package me.nageshk.omnichannel.app.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import me.nageshk.omnichannel.db.user.Availability;
import me.nageshk.omnichannel.security.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The authenticated user's own profile and two-factor enrolment. */
@RestController
@RequestMapping("/api/users/me")
public class UserController {

    public record ConfirmRequest(@NotBlank String secret, @NotBlank String otp) {}

    public record DisableRequest(@NotBlank String otp) {}
    public record AvailabilityRequest(Availability.Status status) {}

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public UserService.CurrentUser me(Principal principal) {
        return users.currentUser(principal.getName());
    }

    @PutMapping("/availability")
    public UserService.CurrentUser availability(Principal principal, @RequestBody(required = false) AvailabilityRequest req) {
        return users.setAvailability(principal.getName(), req == null ? null : req.status());
    }

    @PostMapping("/two-factor/initiate")
    public UserService.TwoFactorSetup initiate(Principal principal) {
        return users.initiateTwoFactor(principal.getName());
    }

    @PostMapping("/two-factor/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(Principal principal, @Valid @RequestBody ConfirmRequest req) {
        users.confirmTwoFactor(principal.getName(), req.secret(), req.otp());
    }

    @PostMapping("/two-factor/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(Principal principal, @Valid @RequestBody DisableRequest req) {
        users.disableTwoFactor(principal.getName(), req.otp());
    }
}
