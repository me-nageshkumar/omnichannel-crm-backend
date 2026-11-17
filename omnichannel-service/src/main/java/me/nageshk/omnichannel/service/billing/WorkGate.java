package me.nageshk.omnichannel.service.billing;

import me.nageshk.omnichannel.common.api.SubscriptionInactiveException;
import me.nageshk.omnichannel.security.entitlement.Entitlement;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.service.inbox.AutomationActor;
import me.nageshk.omnichannel.service.inbox.CampaignActor;
import me.nageshk.omnichannel.service.inbox.ChatbotActor;
import me.nageshk.omnichannel.service.inbox.SendActor;
import org.springframework.stereotype.Component;

/**
 * Keeps work that has no user inside the customer's plan. A feature the plan lacks must stop running, not only
 * disappear from the screen, and a customer whose subscription has ended sends nothing. Each feature is stood for
 * by the authority that manages it.
 */
@Component
public class WorkGate {

    public static final String CAMPAIGNS = "campaign:manage";
    public static final String AUTOMATIONS = "automation:manage";
    public static final String CHATBOTS = "chatbot:manage";

    private final Entitlements entitlements;

    public WorkGate(Entitlements entitlements) {
        this.entitlements = entitlements;
    }

    /**
     * Before a message leaves: refuses for a read-only customer, and for a campaign, rule or chatbot whose feature
     * the plan no longer includes, which is what stops work already in flight at its next message.
     *
     * @throws SubscriptionInactiveException when the send may not happen
     */
    public void requireSend(String workspaceId, SendActor actor) {
        Entitlement e = entitlements.ofWorkspace(workspaceId);
        if (e.readOnly()) throw new SubscriptionInactiveException("The subscription has ended");
        String anchor = anchorOf(actor);
        if (anchor != null && !e.allows(anchor)) throw new SubscriptionInactiveException("The plan does not allow this");
    }

    /** Whether the brand's customer may run the feature now. */
    public boolean allows(String workspaceId, String anchor) {
        Entitlement e = entitlements.ofWorkspace(workspaceId);
        return !e.readOnly() && e.allows(anchor);
    }

    private static String anchorOf(SendActor actor) {
        if (actor instanceof CampaignActor) return CAMPAIGNS;
        if (actor instanceof AutomationActor) return AUTOMATIONS;
        if (actor instanceof ChatbotActor) return CHATBOTS;
        return null;
    }
}
