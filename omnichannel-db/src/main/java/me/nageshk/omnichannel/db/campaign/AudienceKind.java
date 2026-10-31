package me.nageshk.omnichannel.db.campaign;

/** Who a campaign writes to: the contacts of a saved filter, the rows of an uploaded CSV, or a CRM segment's members. */
public enum AudienceKind {
    FILTER, CSV, SEGMENT
}
