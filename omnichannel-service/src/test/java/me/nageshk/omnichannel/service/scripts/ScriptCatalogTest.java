package me.nageshk.omnichannel.service.scripts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import tools.jackson.databind.ObjectMapper;

class ScriptCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final byte[] CODE = "console.log('hi')".getBytes(StandardCharsets.UTF_8);

    private Resource manifest(String sha) {
        String json = "{\"page\":{\"version\":\"abcdef123456\",\"sha256\":\"" + sha + "\",\"size\":" + CODE.length + ",\"builtAt\":\"2026-09-15T00:00:00Z\"}}";
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void loadsScriptsListedInManifestAndExposesManifest() {
        String sha = ScriptCatalog.sha256Hex(CODE);
        ScriptCatalog catalog = new ScriptCatalog(mapper, manifest(sha), Map.of("page", new ByteArrayResource(CODE))::get);

        assertThat(catalog.manifest().get("page").get("sha256").asString()).isEqualTo(sha);
        assertThat(catalog.find("page")).isPresent();
        assertThat(catalog.find("page").get().version()).isEqualTo("abcdef123456");
        assertThat(catalog.find("page").get().bytes()).isEqualTo(CODE);
        assertThat(catalog.find("nope")).isEmpty();
    }

    @Test
    void failsFastOnHashMismatch() {
        assertThatThrownBy(() -> new ScriptCatalog(mapper, manifest("0".repeat(64)), Map.of("page", new ByteArrayResource(CODE))::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("page");
    }

    @Test
    void failsFastOnMissingScriptResource() {
        String sha = ScriptCatalog.sha256Hex(CODE);
        assertThatThrownBy(() -> new ScriptCatalog(mapper, manifest(sha), name -> null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void committedResourcesAreConsistent() {
        // Guards the generated files in src/main/resources against hand edits or a forgotten rebuild.
        ScriptCatalog catalog = new ScriptCatalog(mapper);
        assertThat(catalog.find("page")).isPresent();
        assertThat(catalog.manifest().get("page").get("version").asString()).hasSize(12);
    }
}
