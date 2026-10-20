package me.nageshk.omnichannel.access;

import java.time.Instant;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** One authority a role may hold, stored so a Super Admin can manage the catalogue. */
@Document("authorities")
@Getter
@Setter
public class AuthorityDefinition {

    public static final Pattern CODE_PATTERN =
            Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*:[a-z0-9]+(-[a-z0-9]+)*$");
    public static final int MAX_CODE_LENGTH = 64;

    public static final Pattern GROUP_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    public static final int MAX_GROUP_LENGTH = 40;
    public static final int MAX_DESCRIPTION_LENGTH = 200;

    public static boolean isValidCode(String code) {
        return code != null && code.length() <= MAX_CODE_LENGTH && CODE_PATTERN.matcher(code).matches();
    }

    public static boolean isValidGroup(String group) {
        return group != null && group.length() <= MAX_GROUP_LENGTH && GROUP_PATTERN.matcher(group).matches();
    }

    @Id
    private String code;

    private String group;
    private RoleLevel level;
    private String description;
    private boolean builtIn;
    private Instant createdAt;
    private String createdBy;
    private Instant updatedAt;
    private String updatedBy;

    @Version
    private Long version;
}
