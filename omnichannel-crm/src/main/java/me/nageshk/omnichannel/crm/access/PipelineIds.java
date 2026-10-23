package me.nageshk.omnichannel.crm.access;

import java.util.Set;

/** The ids of a brand's pipelines. */
public interface PipelineIds {

    /** Every pipeline, active or not. */
    Set<String> existing(String workspaceId);

    /** The pipelines that can be offered for new deals. */
    Set<String> active(String workspaceId);
}
