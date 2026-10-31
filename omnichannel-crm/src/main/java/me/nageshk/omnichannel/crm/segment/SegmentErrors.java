package me.nageshk.omnichannel.crm.segment;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.crm.platform.CampaignRef;
import org.springframework.http.HttpStatus;

/** The failures segments answer with (spec D §5). */
public final class SegmentErrors {

    public static final String INVALID = "INVALID_SEGMENT";
    public static final String TOO_BROAD = "SEGMENT_TOO_BROAD";
    public static final String NAME_TAKEN = "SEGMENT_NAME_TAKEN";
    public static final String IN_USE = "SEGMENT_IN_USE";
    public static final String TIMEOUT = "SEGMENT_TIMEOUT";

    private SegmentErrors() {}

    /** 400: every problem found, keyed by its path ({@code name}, {@code rules[1].rules[0].value}, …). */
    public static CodedException invalid(Map<String, String> fieldErrors) {
        return new CodedException(HttpStatus.BAD_REQUEST, INVALID, null, "The segment has " + fieldErrors.size()
                + (fieldErrors.size() == 1 ? " problem" : " problems"), fieldErrors);
    }

    /** 400: a step of the resolution materialised more ids than the CRM will hold in memory. */
    public static CodedException tooBroad() {
        return new CodedException(HttpStatus.BAD_REQUEST, TOO_BROAD, null,
                "The segment matches too many contacts to resolve; narrow its rules");
    }

    /** 409: another segment of the brand has this name (ignoring case). */
    public static CodedException nameTaken() {
        return new CodedException(HttpStatus.CONFLICT, NAME_TAKEN, "name", "A segment with this name already exists");
    }

    /** 409: unfinished campaigns still use the segment; their names in the message, ids in {@code campaignIds}. */
    public static CodedException inUse(List<CampaignRef> campaigns) {
        String names = campaigns.stream().map(CampaignRef::name).collect(Collectors.joining(", "));
        String ids = campaigns.stream().map(CampaignRef::id).collect(Collectors.joining(","));
        return new CodedException(HttpStatus.CONFLICT, IN_USE, null, "Used by campaigns: " + names,
                Map.of("campaignIds", ids));
    }

    /** 503: a query ran past its time limit. */
    public static CodedException timeout() {
        return new CodedException(HttpStatus.SERVICE_UNAVAILABLE, TIMEOUT, null,
                "Resolving the segment took too long; narrow its rules and try again");
    }
}
