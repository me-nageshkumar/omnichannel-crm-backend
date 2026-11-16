package me.nageshk.omnichannel.app.config;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

/**
 * Settings from the MongoDB {@code appConfig} document (spec 2026-11-16), a pre-production stop-gap: the secrets
 * sealing key and the pages base URL. They become a property source below the command line, system properties and
 * environment variables (which still win) and above application.yml. A missing key is generated and stored; a pages
 * URL given in the environment is stored for the next plain start. A key from the environment is never stored, and
 * no key is ever logged.
 */
public class AppConfigLoader implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE = "appConfig";
    static final String KEYS_FIELD = "secretsKeys";
    static final String PAGES_FIELD = "pagesBaseUrl";
    static final String KEYS_PROPERTY = "omnichannel.secrets.keys";
    static final String PAGES_PROPERTY = "omnichannel.pages.base-url";
    static final String PAGES_ENV = "OMNICHANNEL_PAGES_BASE_URL";
    private static final List<String> ABOVE = List.of("commandLineArgs", "systemProperties", "systemEnvironment");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final java.time.format.DateTimeFormatter ID_TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Log log;

    public AppConfigLoader(DeferredLogFactory logs) {
        this(logs.getLog(AppConfigLoader.class));
    }

    AppConfigLoader(Log log) {
        this.log = log;
    }

    @Override
    public int getOrder() {
        // after application.yml is loaded, so spring.mongodb.uri and omnichannel.app-config.enabled resolve
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!enabled(env)) return;
        String uri = env.getProperty("spring.mongodb.uri", "mongodb://localhost:27017/omnichannel_crm");
        try (AppConfigStore store = MongoAppConfigStore.connect(uri)) {
            apply(env, store);
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    void apply(ConfigurableEnvironment env, AppConfigStore store) {
        if (!enabled(env)) return;
        try {
            Map<String, Object> doc = store.read();
            Map<String, Object> props = new LinkedHashMap<>();

            if (blank(env.getProperty(KEYS_PROPERTY))) {
                Object stored = doc.get(KEYS_FIELD);
                List<String> keys = keys(stored);
                if (keys.isEmpty() && !empty(stored)) {
                    log.warn("appConfig: appConfig.secretsKeys is not a list of id:base64 keys; no secrets key is set");
                } else if (keys.isEmpty() && store.sealedSecretsExist()) {
                    // a new key could not open what the lost one sealed: ask for the old key instead of hiding it
                    log.warn("appConfig: no secrets key is set but sealed secrets exist; put the key that sealed them in"
                            + " appConfig.secretsKeys or OMNICHANNEL_SECRETS_KEYS");
                } else if (keys.isEmpty()) {
                    String generated = generateKey();
                    store.saveIfAbsent(KEYS_FIELD, List.of(generated));
                    keys = keys(store.read().get(KEYS_FIELD));
                    if (keys.contains(generated)) {
                        log.info("appConfig: no secrets key was set; a new one was generated and stored in appConfig");
                    }
                }
                if (!keys.isEmpty()) props.put(KEYS_PROPERTY, String.join(",", keys));
            }

            String envUrl = env.getProperty(PAGES_ENV);
            if (!blank(envUrl)) {
                if (!envUrl.equals(doc.get(PAGES_FIELD))) {
                    store.save(PAGES_FIELD, envUrl);
                    log.info("appConfig: stored the pages base URL from " + PAGES_ENV + " for later starts");
                }
            } else if (doc.get(PAGES_FIELD) instanceof String url && !blank(url)) {
                props.put(PAGES_PROPERTY, url.strip());
            }

            if (!props.isEmpty()) add(env.getPropertySources(), new MapPropertySource(SOURCE, props));
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    private void warn(RuntimeException e) {
        log.warn("appConfig: settings could not be read from MongoDB (" + e.getClass().getSimpleName()
                + "); starting with the environment and application.yml only");
    }

    private static boolean enabled(ConfigurableEnvironment env) {
        return env.getProperty("omnichannel.app-config.enabled", Boolean.class, true);
    }

    /** Right below the sources that must still win; first when none of them is present (tests). */
    private static void add(MutablePropertySources sources, MapPropertySource source) {
        String after = null;
        for (String name : ABOVE) if (sources.contains(name)) after = name;
        if (after == null) sources.addFirst(source);
        else sources.addAfter(after, source);
    }

    /** Keys stored as a list, or as one comma-separated text like the environment variable; anything else: none. */
    private static List<String> keys(Object stored) {
        List<String> out = new ArrayList<>();
        if (stored instanceof String text) stored = List.of((Object[]) text.split(","));
        if (stored instanceof List<?> list) {
            for (Object k : list) {
                if (!(k instanceof String s)) return List.of();
                if (!blank(s)) out.add(s.strip());
            }
        }
        return out;
    }

    private static boolean empty(Object stored) {
        return stored == null || (stored instanceof List<?> l && l.isEmpty()) || (stored instanceof String t && t.isBlank());
    }

    /** A fresh key under its own id ("g" + when), so it can never clash with a hand-made or earlier key's id. */
    static String generateKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String id = "g" + ID_TIME.format(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        return id + ":" + Base64.getEncoder().encodeToString(bytes);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
