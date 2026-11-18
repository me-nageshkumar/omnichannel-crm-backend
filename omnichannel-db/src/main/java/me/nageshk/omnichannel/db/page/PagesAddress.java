package me.nageshk.omnichannel.db.page;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A brand's own link address (spec 2026-11-18 link addresses §5.2): one document per brand that has or had one.
 * {@code baseUrl}, {@code host} and the status fields are null while the brand has none; the document stays for its
 * former hosts. Checks write their fields without touching {@code version}; the screen's saves use it.
 */
@Document("pagesAddresses")
@CompoundIndex(name = "former_host", def = "{'formerHosts.host': 1}")
@Getter
@Setter
public class PagesAddress {

    @Id
    private String id;
    @Indexed(unique = true)
    private String workspaceId;
    private String customerId;
    private String baseUrl;
    @Indexed
    private String host;
    private PagesAddressStatus status;
    private Instant checkedAt;
    private String lastError;
    private int failures;
    private String checkNonce;
    private Instant setAt;
    private String setBy;
    private List<FormerHost> formerHosts = new ArrayList<>();
    @Version
    private Long version;
}
