package me.nageshk.omnichannel.scripts;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** Scripts the server delivers to the extension, loaded from the classpath and hash-verified at startup. */
@Component
public class ScriptCatalog {

    public record Script(String name, String version, String sha256, byte[] bytes) {}

    private static final Logger log = LoggerFactory.getLogger(ScriptCatalog.class);
    private static final String BASE = "wabridge/scripts/";

    private final JsonNode manifest;
    private final Map<String, Script> scripts = new LinkedHashMap<>();

    @Autowired
    public ScriptCatalog(ObjectMapper mapper) {
        this(mapper, new ClassPathResource(BASE + "manifest.json"), name -> new ClassPathResource(BASE + name + ".js"));
    }

    ScriptCatalog(ObjectMapper mapper, Resource manifestResource, Function<String, Resource> scriptLoader) {
        try (InputStream in = manifestResource.getInputStream()) {
            this.manifest = mapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read script manifest " + manifestResource, e);
        }
        manifest.properties().forEach(entry -> {
            String name = entry.getKey();
            JsonNode meta = entry.getValue();
            Resource res = scriptLoader.apply(name);
            if (res == null || !res.exists()) {
                throw new IllegalStateException("script resource missing for '" + name + "'");
            }
            byte[] bytes;
            try (InputStream in = res.getInputStream()) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("cannot read script '" + name + "'", e);
            }
            String actual = sha256Hex(bytes);
            String expected = meta.path("sha256").asString();
            if (!actual.equals(expected)) {
                throw new IllegalStateException("script '" + name + "' sha256 mismatch: manifest=" + expected + " actual=" + actual
                        + " (run `npm run build` in extension/ and commit the resources)");
            }
            scripts.put(name, new Script(name, meta.path("version").asString(), actual, bytes));
            log.info("script '{}' version {} ({} bytes) ready", name, meta.path("version").asString(), bytes.length);
        });
    }

    public JsonNode manifest() {
        return manifest;
    }

    public Optional<Script> find(String name) {
        return Optional.ofNullable(scripts.get(name));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
