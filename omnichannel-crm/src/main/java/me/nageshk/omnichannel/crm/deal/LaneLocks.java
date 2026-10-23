package me.nageshk.omnichannel.crm.deal;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * Serializes every change to a lane's positions (ruling R18): reading the neighbours, writing the card and re-spacing
 * the lane happen under the lane's lock, so two re-spaces of one lane never interleave and two appends never take the
 * same position. A lane is keyed {@code workspaceId:pipelineId:stageId}.
 *
 * <p>Callers take every lane they change in one call (a cross-lane move takes its source and target lane together);
 * implementations lock them in a fixed order, so callers must not nest calls.
 *
 * <p>{@link InJvmLaneLocks} only serializes within one JVM, which is enough while the CRM runs in the single app
 * instance (spec 3). A CRM split out into its own service, or run on several instances, must swap in a lease held in
 * Mongo (or another shared store) behind this interface.
 */
public interface LaneLocks {

    /** One lane of a pipeline. */
    record Lane(String pipelineId, String stageId) {}

    /** Runs {@code work} holding the locks of all {@code lanes} of the brand. */
    <T> T withLanes(String workspaceId, Collection<Lane> lanes, Supplier<T> work);

    /** Runs {@code work} holding the lane's lock. */
    default <T> T withLane(String workspaceId, String pipelineId, String stageId, Supplier<T> work) {
        return withLanes(workspaceId, List.of(new Lane(pipelineId, stageId)), work);
    }
}
