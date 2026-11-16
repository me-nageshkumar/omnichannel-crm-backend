package me.nageshk.omnichannel.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

class AppConfigLoaderTest {

    private static final String KEY = "k9:" + Base64.getEncoder().encodeToString(new byte[32]);

    /** An in-memory store; {@code raceKey} is what another start "wrote" first, seen by saveIfAbsent. */
    static final class MemoryStore implements AppConfigStore {
        final Map<String, Object> doc = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        Object raceKey;
        RuntimeException failure;

        @Override
        public Map<String, Object> read() {
            if (failure != null) throw failure;
            return new LinkedHashMap<>(doc);
        }

        @Override
        public void saveIfAbsent(String field, Object value) {
            writes.add(field);
            if (raceKey != null && field.equals("secretsKeys")) doc.putIfAbsent(field, raceKey);
            Object now = doc.get(field);
            boolean empty = now == null || (now instanceof List<?> l && l.isEmpty()) || (now instanceof String t && t.isBlank());
            if (empty) doc.put(field, value);
        }

        @Override
        public void save(String field, Object value) {
            writes.add(field);
            doc.put(field, value);
        }

        @Override
        public void close() {}
    }

    private final List<String> logged = new ArrayList<>();

    private AppConfigLoader loader() {
        Log log = (Log) Proxy.newProxyInstance(Log.class.getClassLoader(), new Class<?>[] {Log.class}, (p, m, args) -> {
            if (args != null && args.length > 0 && args[0] != null) logged.add(String.valueOf(args[0]));
            return m.getReturnType() == boolean.class ? true : null;
        });
        return new AppConfigLoader(log);
    }

    private static MockEnvironment env() {
        // what application.yml resolves to with no environment variables set
        return new MockEnvironment().withProperty("omnichannel.secrets.keys", "")
                .withProperty("omnichannel.pages.base-url", "http://localhost:8080");
    }

    @Test
    void storedValuesBecomeProperties() {
        MemoryStore store = new MemoryStore();
        store.doc.put("secretsKeys", List.of(KEY));
        store.doc.put("pagesBaseUrl", "https://tunnel.example");
        MockEnvironment env = env();
        loader().apply(env, store);
        assertThat(env.getProperty("omnichannel.secrets.keys")).isEqualTo(KEY);
        assertThat(env.getProperty("omnichannel.pages.base-url")).isEqualTo("https://tunnel.example");
        assertThat(store.writes).isEmpty();
    }

    @Test
    void environmentWinsOverStored() {
        MemoryStore store = new MemoryStore();
        store.doc.put("secretsKeys", List.of(KEY));
        MockEnvironment env = env().withProperty("omnichannel.secrets.keys", "e1:from-env");
        loader().apply(env, store);
        assertThat(env.getProperty("omnichannel.secrets.keys")).isEqualTo("e1:from-env");
        assertThat(store.writes).isEmpty();
    }

    @Test
    void missingKeyIsGeneratedAndStored() {
        MemoryStore store = new MemoryStore();
        MockEnvironment env = env();
        loader().apply(env, store);
        String keys = env.getProperty("omnichannel.secrets.keys");
        assertThat(keys).startsWith("k1:");
        assertThat(Base64.getDecoder().decode(keys.substring(3))).hasSize(32);
        assertThat(store.doc.get("secretsKeys")).isEqualTo(List.of(keys));
    }

    @Test
    void concurrentGenerationUsesTheStoredKey() {
        MemoryStore store = new MemoryStore();
        store.raceKey = List.of(KEY);
        MockEnvironment env = env();
        loader().apply(env, store);
        assertThat(env.getProperty("omnichannel.secrets.keys")).isEqualTo(KEY);
    }

    @Test
    void envPagesUrlIsStored() {
        MemoryStore store = new MemoryStore();
        store.doc.put("secretsKeys", List.of(KEY));
        store.doc.put("pagesBaseUrl", "https://old.example");
        MockEnvironment env = env().withProperty("OMNICHANNEL_PAGES_BASE_URL", "https://new.example")
                .withProperty("omnichannel.pages.base-url", "https://new.example");
        loader().apply(env, store);
        assertThat(store.doc.get("pagesBaseUrl")).isEqualTo("https://new.example");
        assertThat(env.getProperty("omnichannel.pages.base-url")).isEqualTo("https://new.example");
    }

    @Test
    void blankValuesCountAsAbsent() {
        MemoryStore store = new MemoryStore();
        store.doc.put("secretsKeys", List.of());
        store.doc.put("pagesBaseUrl", "  ");
        MockEnvironment env = env();
        loader().apply(env, store);
        assertThat(env.getProperty("omnichannel.secrets.keys")).startsWith("k1:");
        assertThat(env.getProperty("omnichannel.pages.base-url")).isEqualTo("http://localhost:8080");
    }

    @Test
    void disabledDoesNothing() {
        MemoryStore store = new MemoryStore();
        MockEnvironment env = env().withProperty("omnichannel.app-config.enabled", "false");
        loader().apply(env, store);
        assertThat(env.getProperty("omnichannel.secrets.keys")).isEmpty();
        assertThat(store.writes).isEmpty();
    }

    @Test
    void storeFailureIsAWarning() {
        MemoryStore store = new MemoryStore();
        store.failure = new IllegalStateException("timed out");
        MockEnvironment env = env();
        loader().apply(env, store);
        assertThat(env.getProperty("omnichannel.secrets.keys")).isEmpty();
        assertThat(logged).anyMatch(l -> l.contains("appConfig"));
    }

    @Test
    void keyNeverLogged() {
        MemoryStore store = new MemoryStore();
        MockEnvironment env = env();
        loader().apply(env, store);
        String key = env.getProperty("omnichannel.secrets.keys");
        assertThat(logged).isNotEmpty().noneMatch(l -> l.contains(key.substring(3)));
    }

    @Test
    void realEnvironmentOrderingAppConfigBelowSystemAboveYaml() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addLast(new MapPropertySource("application.yml",
                Map.of("omnichannel.secrets.keys", "", "omnichannel.pages.base-url", "http://localhost:8080")));
        MemoryStore store = new MemoryStore();
        store.doc.put("secretsKeys", List.of(KEY));
        store.doc.put("pagesBaseUrl", "https://stored.example");
        loader().apply(env, store);
        List<String> names = env.getPropertySources().stream().map(ps -> ps.getName()).toList();
        assertThat(names.indexOf("appConfig")).isGreaterThan(names.indexOf("systemEnvironment"))
                .isLessThan(names.indexOf("application.yml"));
        assertThat(env.getProperty("omnichannel.pages.base-url")).isEqualTo("https://stored.example");
        assertThat(env.getProperty("omnichannel.secrets.keys")).isEqualTo(KEY);
        System.setProperty("omnichannel.pages.base-url", "https://system.example");
        try {
            assertThat(env.getProperty("omnichannel.pages.base-url")).isEqualTo("https://system.example");
        } finally {
            System.clearProperty("omnichannel.pages.base-url");
        }
    }
}
