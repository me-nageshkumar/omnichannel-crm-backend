package me.nageshk.omnichannel.crm.settings;

import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.security.entitlement.Entitlement;

/**
 * Whether the CRM is on in a brand and under which limits. A customer without a plan is ruled by the brand's own
 * switch and limits, set by platform staff. For a customer on a plan the plan decides: the CRM is on in every
 * brand when the plan (or an add-on) includes it and off otherwise, and a limit the plan sets replaces the brand's.
 */
public final class CrmSwitch {

    /** The authority that stands for "this customer has the CRM". */
    static final String ANCHOR = "crm:view";

    private CrmSwitch() {}

    public static boolean planDecides(Entitlement e) {
        return !e.unrestricted();
    }

    /** For a customer on a plan: whether the plan includes the CRM. */
    public static boolean on(Entitlement e) {
        return e.allows(ANCHOR);
    }

    /** Puts what is in force on the settings in hand; they are for reading, never saved back as a whole. */
    public static CrmSettings apply(Entitlement e, CrmSettings s) {
        if (!planDecides(e)) return s;
        s.setEnabled(on(e));
        e.limit(LimitKey.CRM_PIPELINES).ifPresent(s::setMaxPipelines);
        e.limit(LimitKey.CRM_CUSTOM_FIELDS).ifPresent(s::setMaxCustomFields);
        return s;
    }
}
