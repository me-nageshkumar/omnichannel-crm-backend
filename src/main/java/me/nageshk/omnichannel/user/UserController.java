package me.nageshk.omnichannel.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public UserService.CurrentUser me(Principal principal) {
        return users.currentUser(principal.getName());
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
