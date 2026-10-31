package me.nageshk.omnichannel.crm.activity;

public enum ActivityType {
    RECORD_CREATED, FIELD_CHANGED, STAGE_CHANGED, DEAL_WON, DEAL_LOST, NOTE_ADDED, TASK_CREATED, TASK_UPDATED,
    TASK_COMPLETED, CALL_LOGGED, MEETING_LOGGED, EMAIL_LOGGED,
    /** A campaign message went out to the contact (spec D C13); payload names the campaign and channel account. */
    CAMPAIGN_SENT,
    /** Reserved for channel messages on the timeline; named without the channel on purpose (spec D C2). */
    MESSAGE_SENT, MESSAGE_RECEIVED,
    LEAD_CONVERTED, CHANNEL_CHANGED, SETTINGS_CHANGED, SYSTEM
}
