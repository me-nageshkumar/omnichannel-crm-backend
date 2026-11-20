package me.nageshk.omnichannel.meta.account;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A Cloud number's Meta side, kept beside the host's phone number ({@code phoneNumberId}). */
@Document("cloudAccounts")
@Getter
@Setter
public class CloudAccount {

    @Id
    private String id;
    @Indexed(name = "phoneNumberId_unique", unique = true)
    private String phoneNumberId;
    @Indexed
    private String workspaceId;
    private String customerId;
    @Indexed
    private String connectionId;
    private String wabaId;
    @Indexed(name = "metaPhoneNumberId_unique", unique = true)
    private String metaPhoneNumberId;
    private String displayPhoneNumber;
    private String verifiedName;
    private String nameStatus;
    private String qualityRating;
    private String messagingLimitTier;
    private String platformType;
    private boolean registered;
    private Instant checkedAt;
    private String lastError;
    private Instant createdAt;
    @Version
    private Long version;
}
