package me.nageshk.omnichannel.app.page;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.service.page.PagesAddresses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asked by the edge (Caddy's on-demand TLS) before it fetches a certificate (spec 2026-11-18 link addresses §5.6):
 * 200 for a host of ours, else 404. Only a direct caller on loopback (or listed in {@code ask-from}) is answered;
 * anything the edge proxied from outside also arrives from loopback, but carries forwarding headers.
 */
@RestController
public class PagesHostController {

    private final PagesAddresses addresses;
    private final Set<String> askFrom;

    public PagesHostController(PagesAddresses addresses, @Value("${omnichannel.pages.ask-from:}") String askFrom) {
        this.addresses = addresses;
        this.askFrom = Arrays.stream(askFrom.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @GetMapping("/internal/pages/host-allowed")
    public ResponseEntity<Void> hostAllowed(@RequestParam(name = "domain", required = false) String domain,
            HttpServletRequest req) {
        if (!direct(req) || !addresses.hostKnown(domain)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().build();
    }

    private boolean direct(HttpServletRequest req) {
        if (req.getHeader("X-Forwarded-For") != null || req.getHeader("Forwarded") != null || req.getHeader("Via") != null
                || req.getHeader("X-Forwarded-Host") != null) {
            return false;
        }
        String remote = req.getRemoteAddr();
        if (remote == null) return false;
        if (askFrom.contains(remote)) return true;
        // the servlet container gives a literal address: compare text, never look a name up
        return remote.startsWith("127.") || remote.equals("::1") || remote.equals("0:0:0:0:0:0:0:1");
    }
}
