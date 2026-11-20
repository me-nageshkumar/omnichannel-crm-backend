package me.nageshk.omnichannel.meta.template;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.meta.api.CloudTemplateInfo;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A Meta template as imported from the brand's WABA; {@code components} is Meta's JSON as it came. Kept, no TTL: a
 * template removed at Meta is marked {@code DELETED} so old messages still show what was sent.
 */
@Document("cloudTemplates")
@CompoundIndex(name = "ws_waba_template_unique", def = "{'workspaceId': 1, 'wabaId': 1, 'metaTemplateId': 1}",
        unique = true)
@Getter
@Setter
public class CloudTemplate {

    public static final String DELETED = "DELETED";

    @Id
    private String id;
    private String workspaceId;
    private String connectionId;
    private String wabaId;
    private String metaTemplateId;
    private String name;
    private String language;
    private String category;
    private String status;
    private String rejectedReason;
    private String parameterFormat;
    private List<Map<String, Object>> components = new ArrayList<>();
    private String headerFormat;
    private List<CloudTemplateInfo.Variable> variables = new ArrayList<>();
    /** Per blank (by key) where its value comes from; a list, as keys hold dots Mongo maps cannot. */
    private List<FillEntry> fills = new ArrayList<>();
    private boolean supported;
    private String unsupportedReason;
    private Instant syncedAt;
    private Instant lastUsedAt;
    @Version
    private Long version;

    public record FillEntry(String key, CloudTemplateInfo.FillKind kind, String attributeKey, String fallback) {}
}
