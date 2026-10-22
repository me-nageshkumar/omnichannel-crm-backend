package me.nageshk.omnichannel.phone;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A one-time code that swaps for a number's device key; only its SHA-256 is stored. */
@Document("connectCodes")
@Getter
@Setter
public class ConnectCode {

    @Id
    private String id;

    @Indexed(unique = true)
    private String codeHash;
    @Indexed
    private String phoneNumberId;
    private Instant expiresAt;
}
