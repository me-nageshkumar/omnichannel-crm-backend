package me.nageshk.omnichannel.db.enablement;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.common.enablement.EnableLevel;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One audited enable or disable of a number, brand, customer, login or brand access. */
@Document("enablementChanges")
@Getter
@Setter
public class EnablementChange {

    @Id
    private String id;

    private String targetType;
    @Indexed
    private String targetId;
    private boolean enabledBefore;
    private boolean enabledAfter;
    private String by;
    private EnableLevel level;
    private Instant at;
}
