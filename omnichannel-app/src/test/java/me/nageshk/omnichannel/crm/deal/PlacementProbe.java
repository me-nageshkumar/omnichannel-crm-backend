package me.nageshk.omnichannel.crm.deal;

import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.pipeline.CrmStage;
import org.springframework.context.ApplicationContext;

/** Test access to the package-private {@link Placement}: places a deal read earlier, as a racing request would. */
public final class PlacementProbe {

    private PlacementProbe() {}

    public static CrmDeal place(ApplicationContext app, CrmContext ctx, CrmDeal asRead, CrmPipeline pipeline,
            String stageId) {
        CrmStage target = Placement.stage(pipeline, stageId).orElseThrow();
        return app.getBean(Placement.class).place(ctx, asRead, pipeline, pipeline, target, 99_999, null, null, null);
    }
}
