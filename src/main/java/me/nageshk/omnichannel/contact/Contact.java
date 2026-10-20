package me.nageshk.omnichannel.contact;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** A person a brand talks to; unique per brand by phone and by WhatsApp chat id. */
@Document("contacts")
@CompoundIndexes({
        @CompoundIndex(name = "ws_phone", def = "{'workspaceId': 1, 'phone': 1}", unique = true,
                partialFilter = "{'phone': {$type: 'string'}}"),
        @CompoundIndex(name = "ws_waId", def = "{'workspaceId': 1, 'waId': 1}", unique = true,
                partialFilter = "{'waId': {$type: 'string'}}"),
        @CompoundIndex(name = "ws_lastMessageAt", def = "{'workspaceId': 1, 'lastMessageAt': -1}"),
        @CompoundIndex(name = "ws_labels", def = "{'workspaceId': 1, 'labels': 1}")
})
@Getter
@Setter
public class Contact {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private String waId;
    private String phone;
    private String name;
    private boolean nameEdited;
    private Set<String> labels = new HashSet<>();
    private Map<String, String> attributes = new HashMap<>();
    private ContactSource source;
    private Instant lastMessageAt;
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    @Version
    private Long version;
}
