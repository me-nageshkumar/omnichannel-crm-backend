package me.nageshk.omnichannel.inbox;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** The last answer to "is this phone number on WhatsApp", one row per workspace and phone. */
@Document("waNumberLookups")
@CompoundIndex(name = "ws_phone", def = "{'workspaceId': 1, 'phone': 1}", unique = true)
@Getter
@Setter
public class WaNumberLookup {

    @Id
    private String id;
    private String workspaceId;
    /** The number whose device answered. */
    private String phoneNumberId;
    /** Normalised digits with the country code, as asked. */
    private String phone;
    private boolean registered;
    /** WhatsApp's {@code <digits>@c.us} id for the phone; null when not registered. */
    private String waPhone;
    /** The {@code @lid} WhatsApp reported for it, when it did. */
    private String lid;
    private Instant checkedAt;
}
