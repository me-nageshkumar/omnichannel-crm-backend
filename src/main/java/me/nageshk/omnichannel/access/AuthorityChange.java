package me.nageshk.omnichannel.access;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One audited write to an authority. Kept after the authority is deleted. */
@Document("authorityChanges")
@Getter
@Setter
public class AuthorityChange {

    public enum Action { CREATED, UPDATED, DELETED }

    @Id
    private String id;

    @Indexed
    private String code;
    private Action action;
    private String groupBefore;
    private String groupAfter;
    private RoleLevel levelBefore;
    private RoleLevel levelAfter;
    private String descriptionBefore;
    private String descriptionAfter;
    private String changedBy;
    private Instant at;
}
