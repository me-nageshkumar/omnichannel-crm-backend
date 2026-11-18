package me.nageshk.omnichannel.db.page;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The platform's link address as saved on the screen (one document); null {@code baseUrl}: the startup value is in
 * use. Former platform hosts stay known until their {@code until}.
 */
@Document("pagesSettings")
@Getter
@Setter
public class PagesSettings {

    public static final String ID = "pages";

    @Id
    private String id = ID;
    private String baseUrl;
    private List<FormerHost> formerHosts = new ArrayList<>();
    private Instant updatedAt;
    private String updatedBy;
    @Version
    private Long version;
}
