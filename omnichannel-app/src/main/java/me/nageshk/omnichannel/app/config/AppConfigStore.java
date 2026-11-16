package me.nageshk.omnichannel.app.config;

import java.util.Map;

/** The {@code appConfig} document: settings read at startup (spec 2026-11-16). */
interface AppConfigStore extends AutoCloseable {

    /** The stored fields; empty when there is no document. */
    Map<String, Object> read();

    /**
     * Sets {@code field} only if the document does not have it yet — absent, null, an empty list or blank text — since
     * another start may have written it first.
     */
    void saveIfAbsent(String field, Object value);

    void save(String field, Object value);

    /** Whether any secret is already sealed (an AI connection's key or an MCP server's secret). */
    boolean sealedSecretsExist();

    @Override
    void close();
}
