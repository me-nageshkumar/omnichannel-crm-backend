package me.nageshk.omnichannel.access;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint guarded by the {@code test:probe} authority. */
@RestController
@RequestMapping("/api/test-probe")
@PreAuthorize("isAuthenticated()")
public class ProbeController {

    @GetMapping
    @PreAuthorize("hasAuthority('test:probe')")
    public String probe() {
        return "ok";
    }
}
