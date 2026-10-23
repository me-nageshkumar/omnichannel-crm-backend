package me.nageshk.omnichannel.crm.access;

import java.util.Set;
import org.springframework.stereotype.Component;

/** Placeholder until the pipeline-access store exists; delete it when that store provides the reader. */
@Component
class NoPipelineAccess implements PipelineAccessReader {

    @Override
    public Set<String> pipelineIdsOf(String workspaceId, String userId) {
        return Set.of();
    }
}
