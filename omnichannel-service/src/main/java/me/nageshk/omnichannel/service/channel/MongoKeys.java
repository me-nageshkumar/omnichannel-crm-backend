package me.nageshk.omnichannel.service.channel;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Keeps JSON storable in Mongo. */
public final class MongoKeys {

    private MongoKeys() {}

    /** Removes, at any depth, the keys Mongo refuses to store: those with a dot or a leading dollar sign. */
    public static void dropRefused(JsonNode node) {
        if (node.isObject()) {
            ((ObjectNode) node).propertyNames().stream().filter(k -> k.contains(".") || k.startsWith("$")).toList()
                    .forEach(((ObjectNode) node)::remove);
        }
        node.forEach(MongoKeys::dropRefused);
    }
}
