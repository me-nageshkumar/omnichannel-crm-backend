package me.nageshk.omnichannel.ai.api;

import java.util.List;
import java.util.Map;

/**
 * Whether a JSON schema fits OpenAI's strict mode: every object closed ({@code additionalProperties: false}) with all
 * its properties required, all the way down, and none of the keywords strict mode refuses. The OpenAI adapter asks for
 * strict output only then; the AI node warns when a reply schema is not.
 */
public final class StrictSchemas {

    private StrictSchemas() {
    }

    public static boolean ready(Object schema) {
        if (!(schema instanceof Map<?, ?> s)) return true;
        if (s.containsKey("$ref") || s.containsKey("oneOf") || s.containsKey("allOf") || s.containsKey("not")
                || s.containsKey("patternProperties")) {
            return false;
        }
        Object type = s.get("type");
        boolean object = "object".equals(type) || (type instanceof List<?> l && l.contains("object"))
                || s.containsKey("properties");
        if (object) {
            if (!Boolean.FALSE.equals(s.get("additionalProperties"))) return false;
            if (!(s.get("properties") instanceof Map<?, ?> props)) return false;
            if (!(s.get("required") instanceof List<?> required) || !required.containsAll(props.keySet())) return false;
            for (Object p : props.values()) {
                if (!ready(p)) return false;
            }
        }
        if (s.containsKey("items") && !ready(s.get("items"))) return false;
        if (s.get("anyOf") instanceof List<?> any) {
            for (Object a : any) {
                if (!ready(a)) return false;
            }
        }
        return true;
    }
}
