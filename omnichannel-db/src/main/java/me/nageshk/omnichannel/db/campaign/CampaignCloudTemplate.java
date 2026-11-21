package me.nageshk.omnichannel.db.campaign;

/**
 * The imported Meta template a Cloud campaign sends (spec 2026-11-21 §5.1); {@code name} and {@code language} are
 * copied at save for display, {@code headerMediaId} is the app's uploaded file for a media header.
 */
public record CampaignCloudTemplate(String cloudTemplateId, String name, String language, String headerMediaId) {
}
