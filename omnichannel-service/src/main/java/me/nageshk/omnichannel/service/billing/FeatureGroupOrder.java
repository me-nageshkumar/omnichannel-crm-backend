package me.nageshk.omnichannel.service.billing;

import java.util.Comparator;
import me.nageshk.omnichannel.db.billing.FeatureGroup;

/** The order feature groups are listed in everywhere. */
public final class FeatureGroupOrder {

    public static final Comparator<FeatureGroup> COMPARATOR =
            Comparator.comparingInt(FeatureGroup::getSortOrder).thenComparing(FeatureGroup::getCode);

    private FeatureGroupOrder() {}
}
