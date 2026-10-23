package me.nageshk.omnichannel.crm.access;

import java.util.Set;
import org.springframework.stereotype.Component;

/** Placeholder until pipelines exist; delete it when the pipeline service provides {@link PipelineIds}. */
@Component
class NoPipelines implements PipelineIds {

    @Override
    public Set<String> existing(String workspaceId) {
        return Set.of();
    }

    @Override
    public Set<String> active(String workspaceId) {
        return Set.of();
    }
}
