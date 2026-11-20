package me.nageshk.omnichannel.meta.connection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.common.secret.SealedSecret;
import me.nageshk.omnichannel.meta.api.ConnectionStatus;
import me.nageshk.omnichannel.meta.api.WebhookSetup;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A brand's own Meta app: its app secret (webhook signatures, the app token) and a system user's token (every Graph
 * call for the app's numbers), both sealed. One app belongs to one brand, since Meta keeps one callback per app.
 */
@Document("metaConnections")
@Getter
@Setter
public class MetaConnection {

    @Id
    private String id;
    @Indexed
    private String workspaceId;
    private String customerId;
    private String name;
    @Indexed(name = "appId_unique", unique = true)
    private String appId;
    private SealedSecret appSecret;
    private String appSecretHint;
    private Instant appSecretSetAt;
    private SealedSecret accessToken;
    private String tokenHint;
    private Instant tokenSetAt;
    @Indexed(name = "webhookKey_unique", unique = true)
    private String webhookKey;
    private String verifyToken;
    private WebhookSetup webhookSetup = WebhookSetup.MANUAL;
    private String webhookError;
    private Instant lastWebhookAt;
    private ConnectionStatus status = ConnectionStatus.UNVERIFIED;
    private Instant checkedAt;
    private String lastError;
    /** The WABAs the token may manage, from the last successful check. */
    private List<String> wabaIds = new ArrayList<>();
    private long ignoredChanges;
    private String lastIgnoredNumber;
    private Instant createdAt;
    private String updatedBy;
    @Version
    private Long version;
}
