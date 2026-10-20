package me.nageshk.omnichannel.contact;

import java.time.Instant;
import java.util.List;

/** A structured contact filter; every part is optional and the parts are ANDed. */
public record ContactFilterSpec(
        String q,
        List<String> labelsAny,
        List<String> labelsAll,
        List<String> labelsNone,
        List<AttributeFilter> attributes,
        List<ContactSource> source,
        Boolean numberHidden,
        Instant createdFrom,
        Instant createdTo,
        Instant lastMessageFrom,
        Instant lastMessageTo) {

    public enum Op { EQUALS, CONTAINS, EXISTS, NOT_EXISTS }

    public record AttributeFilter(String attributeId, Op op, String value) {}
}
