package me.nageshk.omnichannel.db.access;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A named bundle of authorities. Authority codes are strings; unknown (removed) codes may linger. */
@Document("roles")
@Getter
@Setter
public class Role {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    private String description;
    private RoleLevel level;
    private Set<String> authorities = new HashSet<>();
    private boolean builtIn;

    @CreatedDate
    private Instant creationTime;

    @LastModifiedDate
    private Instant lastUpdateTime;
}
