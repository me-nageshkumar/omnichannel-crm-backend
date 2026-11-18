package me.nageshk.omnichannel.app.page;

import me.nageshk.omnichannel.service.page.PagesAddressChecker;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The far end of the link address check (spec 2026-11-18 link addresses §5.4): answers only a nonce it handed out. */
@RestController
public class PagesCheckController {

    private final PagesAddressChecker checker;

    public PagesCheckController(PagesAddressChecker checker) {
        this.checker = checker;
    }

    @GetMapping(value = "/t/-/check/{nonce}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> check(@PathVariable String nonce) {
        if (!checker.knows(nonce)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("omnichannel-pages " + nonce);
    }
}
