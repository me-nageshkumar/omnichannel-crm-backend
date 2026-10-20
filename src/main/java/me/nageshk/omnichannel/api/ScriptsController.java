package me.nageshk.omnichannel.api;

import tools.jackson.databind.JsonNode;
import me.nageshk.omnichannel.scripts.ScriptCatalog;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the extension's server-delivered scripts (see ScriptCatalog). */
@RestController
@PreAuthorize("hasAuthority('DEVICE') or hasAuthority('SUPER_ADMIN')")
@RequestMapping("/api/scripts")
public class ScriptsController {

    private static final MediaType JAVASCRIPT = new MediaType("application", "javascript", StandardCharsets.UTF_8);

    private final ScriptCatalog catalog;

    public ScriptsController(ScriptCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public JsonNode manifest() {
        return catalog.manifest();
    }

    @GetMapping("/{name:[a-z0-9_-]+}")
    public ResponseEntity<?> script(
            @PathVariable String name,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        return catalog.find(name)
                .<ResponseEntity<?>>map(script -> {
                    String etag = "\"" + script.sha256() + "\"";
                    if (etag.equals(ifNoneMatch)) {
                        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
                    }
                    return ResponseEntity.ok()
                            .contentType(JAVASCRIPT)
                            .eTag(etag)
                            .cacheControl(CacheControl.noCache().cachePrivate().mustRevalidate())
                            .body(script.bytes());
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("code", "NOT_FOUND", "message", "no script named " + name)));
    }
}
