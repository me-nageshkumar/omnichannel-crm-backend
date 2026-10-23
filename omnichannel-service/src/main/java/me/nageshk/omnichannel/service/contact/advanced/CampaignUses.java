package me.nageshk.omnichannel.service.contact.advanced;

import java.util.List;
import me.nageshk.omnichannel.common.api.FilterInUseException;

/** Port to the campaigns that still need a label or attribute. */
public interface CampaignUses {

    /**
     * The unfinished campaigns of the workspace whose audience uses the id, sorted by name.
     *
     * @param usesField {@code "labelIds"} or {@code "attributeIds"}
     */
    List<FilterInUseException.Ref> using(String workspaceId, String usesField, String id);
}
