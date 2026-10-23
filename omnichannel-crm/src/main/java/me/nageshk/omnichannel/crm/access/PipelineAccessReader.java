package me.nageshk.omnichannel.crm.access;

import java.util.Set;

/** The pipelines an admin assigned to a user. */
public interface PipelineAccessReader {

    Set<String> pipelineIdsOf(String workspaceId, String userId);
}
