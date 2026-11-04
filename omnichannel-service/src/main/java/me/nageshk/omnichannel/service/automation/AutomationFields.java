package me.nageshk.omnichannel.service.automation;

import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.db.automation.EventData;

/** Reads an event's facts into the map the conditions are evaluated against, keyed by field code. */
public final class AutomationFields {

    private AutomationFields() {
    }

    /** Only the keys the event's data carries are present; {@code contact.inCrm} is the live answer, not the snapshot. */
    public static Map<String, Object> values(EventData d, boolean inCrmNow) {
        Map<String, Object> v = new LinkedHashMap<>();
        if (d.chat() != null) {
            v.put("chat.new", d.chat().isNew());
        }
        if (d.message() != null) {
            put(v, "message.text", d.message().text());
            put(v, "message.type", d.message().type());
            v.put("message.ad", d.message().ad() != null);
            if (d.message().ad() != null) {
                put(v, "message.ad.headline", d.message().ad().headline());
                put(v, "message.ad.sourceId", d.message().ad().sourceId());
            }
        }
        if (d.number() != null) {
            put(v, "number", d.number().id());
        }
        if (d.message() != null || d.contact() != null) {
            v.put("contact.inCrm", inCrmNow);
        }
        if (d.contact() != null) {
            put(v, "contact.name", d.contact().name());
        }
        if (d.pipeline() != null) {
            put(v, "pipeline", d.pipeline().id());
        }
        if (d.toLane() != null) {
            put(v, "toLane", d.toLane().id());
            put(v, "toLane.type", d.toLane().type());
        }
        if (d.fromLane() != null) {
            put(v, "fromLane", d.fromLane().id());
        }
        if (d.deal() != null) {
            put(v, "deal.amount", d.deal().amount());
            if (d.deal().owner() != null) {
                put(v, "deal.owner", d.deal().owner().id());
            }
        }
        return v;
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }
}
