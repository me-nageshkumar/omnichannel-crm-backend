package me.nageshk.omnichannel.crm.activity;

public enum ActivityType {
    RECORD_CREATED, FIELD_CHANGED, STAGE_CHANGED, DEAL_WON, DEAL_LOST, NOTE_ADDED, TASK_CREATED, TASK_UPDATED,
    TASK_COMPLETED, CALL_LOGGED, MEETING_LOGGED, EMAIL_LOGGED,
    /** A campaign message went out to the contact (spec D C13); payload names the campaign and channel account. */
    CAMPAIGN_SENT,
    /** The contact opened a message page (the page a Cloud interactive message was sent as); first open only. */
    MESSAGE_PAGE_OPENED,
    /** The contact tapped a button, picked a row, opened the link or shared a location on a message page. */
    MESSAGE_PAGE_ACTION,
    /** Reserved for channel messages on the timeline; named without the channel on purpose (spec D C2). */
    MESSAGE_SENT, MESSAGE_RECEIVED,
    LEAD_CONVERTED, CHANNEL_CHANGED, SETTINGS_CHANGED, SYSTEM
}
