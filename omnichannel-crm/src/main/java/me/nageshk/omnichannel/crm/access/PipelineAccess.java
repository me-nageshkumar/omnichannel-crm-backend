package me.nageshk.omnichannel.crm.access;

import java.util.Set;
import org.springframework.data.mongodb.core.query.Criteria;

/** Which pipelines a user may see: all of them, or exactly the listed ids (possibly none). */
public record PipelineAccess(boolean all, Set<String> pipelineIds) {

    public static final PipelineAccess ALL = new PipelineAccess(true, Set.of());

    public PipelineAccess {
        pipelineIds = Set.copyOf(pipelineIds);
    }

    public boolean allows(String pipelineId) {
        return all || pipelineIds.contains(pipelineId);
    }

    /** Empty criteria for everything; otherwise {@code field in ids}, which matches nothing for an empty set. */
    public Criteria criteria(String field) {
        return all ? new Criteria() : Criteria.where(field).in(pipelineIds);
    }
}
