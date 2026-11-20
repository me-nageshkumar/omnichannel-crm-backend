package me.nageshk.omnichannel.meta.connection;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.meta.api.ConnectionStatus;
import me.nageshk.omnichannel.meta.api.WebhookSetup;

/** A connection as the console sees it: hints and times only, never a secret. */
public record ConnectionView(String id, String name, String appId, String appSecretHint, Instant appSecretSetAt,
        String tokenHint, Instant tokenSetAt, ConnectionStatus status, Instant checkedAt, String lastError,
        Webhook webhook, List<NumberRef> numbers, long ignoredChanges, String lastIgnoredNumber, Long version) {

    public record Webhook(String url, String verifyToken, WebhookSetup setup, String error, Instant lastWebhookAt) {}

    public record NumberRef(String phoneNumberId, String number, String label) {}

    /** What the console sends: secrets only to set or replace them. */
    public record Save(String name, String appId, String appSecret, String accessToken) {}

    /** The check's answer: the connection, and the WABAs and numbers the token may use. */
    public record Check(ConnectionView connection, List<Account> accounts) {}

    public record Account(String wabaId, String wabaName, List<MetaNumber> numbers) {}

    public record MetaNumber(String metaPhoneNumberId, String displayPhoneNumber, String verifiedName,
            String nameStatus, String qualityRating, String platformType, String phoneNumberId) {}
}
