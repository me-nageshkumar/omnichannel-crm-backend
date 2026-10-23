package me.nageshk.omnichannel.security.access;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.api.AuthorityInUseException;
import me.nageshk.omnichannel.common.api.DuplicateException;
import me.nageshk.omnichannel.common.api.ForbiddenException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.access.AuthorityChange;
import me.nageshk.omnichannel.db.access.AuthorityChangeRepository;
import me.nageshk.omnichannel.db.access.AuthorityDefinition;
import me.nageshk.omnichannel.db.access.AuthorityDefinitionRepository;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.security.access.AuthorityCheckScanner.FoundCheck;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Super Admin management of authorities: validation, the editing rules, audit and cache eviction. */
@Service
public class AuthorityService {

    private final AuthorityDefinitionRepository definitions;
    private final AuthorityChangeRepository changes;
    private final AuthorityCatalog catalog;
    private final RoleCache roles;
    private final AuthorityCheckScanner scanner;

    public AuthorityService(AuthorityDefinitionRepository definitions, AuthorityChangeRepository changes,
            AuthorityCatalog catalog, RoleCache roles, AuthorityCheckScanner scanner) {
        this.definitions = definitions;
        this.changes = changes;
        this.catalog = catalog;
        this.roles = roles;
        this.scanner = scanner;
    }

    /** Every authority, sorted by group, then code. */
    public List<AuthorityDefinition> list() {
        return catalog.all().stream().sorted(Comparator.comparing(AuthorityDefinition::getGroup)
                .thenComparing(AuthorityDefinition::getCode)).toList();
    }

    /** Codes checked in code that have no authority document, sorted by code. */
    public List<FoundCheck> needsCreating() {
        return scanner.found().values().stream().filter(f -> catalog.byCode(f.code()).isEmpty()).toList();
    }

    /** The scan result for the code, or null when no {@code @PreAuthorize} checks it. */
    public FoundCheck checkOf(String code) {
        return scanner.found().get(code);
    }

    /** Names of the roles that hold the code, sorted. */
    public List<String> rolesHolding(String code) {
        return roles.all().stream().filter(r -> r.getAuthorities().contains(code)).map(Role::getName).sorted().toList();
    }

    public AuthorityDefinition create(String code, String group, RoleLevel level, String description, String actor) {
        String trimmed = trim(description);
        Map<String, String> problems = new LinkedHashMap<>();
        if (!AuthorityDefinition.isValidCode(code)) {
            problems.put("code", "must be thing:action in lower-case letters, digits and single hyphens, at most "
                    + AuthorityDefinition.MAX_CODE_LENGTH + " characters");
        }
        validate(group, level, trimmed, problems);
        if (!problems.isEmpty()) throw new ValidationException(problems);
        if (definitions.existsById(code)) throw duplicate();

        Instant now = Instant.now();
        AuthorityDefinition d = new AuthorityDefinition();
        d.setCode(code);
        d.setGroup(group);
        d.setLevel(level);
        d.setDescription(trimmed);
        d.setCreatedAt(now);
        d.setCreatedBy(actor);
        d.setUpdatedAt(now);
        d.setUpdatedBy(actor);
        try {
            d = definitions.save(d);
        } catch (DuplicateKeyException e) {
            throw duplicate();
        }
        catalog.evictAll();
        audit(d.getCode(), AuthorityChange.Action.CREATED, null, d, actor);
        return d;
    }

    public AuthorityDefinition update(String code, String group, RoleLevel level, String description, String actor) {
        return update(existing(code), group, level, description, actor);
    }

    /** Applies the edit to {@code current} as loaded; a stale copy fails with an optimistic-locking error. */
    public AuthorityDefinition update(AuthorityDefinition current, String group, RoleLevel level, String description,
            String actor) {
        String trimmed = trim(description);
        Map<String, String> problems = new LinkedHashMap<>();
        validate(group, level, trimmed, problems);
        if (!problems.isEmpty()) throw new ValidationException(problems);
        if (level != current.getLevel()) {
            if (current.isBuiltIn()) throw new ForbiddenException("the level of a built-in authority cannot be changed");
            requireUnused(current.getCode(), "change its level");
        }

        AuthorityDefinition before = copy(current);
        current.setGroup(group);
        current.setLevel(level);
        current.setDescription(trimmed);
        current.setUpdatedAt(Instant.now());
        current.setUpdatedBy(actor);
        AuthorityDefinition saved = definitions.save(current);
        catalog.evictAll();
        audit(saved.getCode(), AuthorityChange.Action.UPDATED, before, saved, actor);
        return saved;
    }

    public void delete(String code, String actor) {
        AuthorityDefinition d = existing(code);
        if (d.isBuiltIn()) throw new ForbiddenException("built-in authorities cannot be deleted");
        requireUnused(code, "delete it");
        definitions.delete(d);
        catalog.evictAll();
        audit(code, AuthorityChange.Action.DELETED, d, null, actor);
    }

    /** The code's changes, newest first; 404 when the code neither exists nor ever existed. */
    public List<AuthorityChange> history(String code) {
        List<AuthorityChange> found = changes.findByCodeOrderByAtDescIdDesc(code);
        if (found.isEmpty() && !definitions.existsById(code)) throw new NotFoundException("no such authority");
        return found;
    }

    private AuthorityDefinition existing(String code) {
        return definitions.findById(code).orElseThrow(() -> new NotFoundException("no such authority"));
    }

    private void requireUnused(String code, String what) {
        List<String> holders = rolesHolding(code);
        if (!holders.isEmpty()) {
            throw new AuthorityInUseException("remove the authority from " + String.join(", ", holders)
                    + " before you " + what, holders);
        }
    }

    private static void validate(String group, RoleLevel level, String description, Map<String, String> problems) {
        if (!AuthorityDefinition.isValidGroup(group)) {
            problems.put("group", "must be lower-case letters, digits and single hyphens, at most "
                    + AuthorityDefinition.MAX_GROUP_LENGTH + " characters");
        }
        if (level == null) problems.put("level", "is required");
        if (description == null || description.isEmpty()
                || description.length() > AuthorityDefinition.MAX_DESCRIPTION_LENGTH) {
            problems.put("description", "must be 1-" + AuthorityDefinition.MAX_DESCRIPTION_LENGTH + " characters");
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static DuplicateException duplicate() {
        return new DuplicateException("code", "an authority with this code already exists");
    }

    private static AuthorityDefinition copy(AuthorityDefinition d) {
        AuthorityDefinition c = new AuthorityDefinition();
        c.setGroup(d.getGroup());
        c.setLevel(d.getLevel());
        c.setDescription(d.getDescription());
        return c;
    }

    private void audit(String code, AuthorityChange.Action action, AuthorityDefinition before,
            AuthorityDefinition after, String actor) {
        AuthorityChange change = new AuthorityChange();
        change.setCode(code);
        change.setAction(action);
        if (before != null) {
            change.setGroupBefore(before.getGroup());
            change.setLevelBefore(before.getLevel());
            change.setDescriptionBefore(before.getDescription());
        }
        if (after != null) {
            change.setGroupAfter(after.getGroup());
            change.setLevelAfter(after.getLevel());
            change.setDescriptionAfter(after.getDescription());
        }
        change.setChangedBy(actor);
        change.setAt(Instant.now());
        changes.save(change);
    }
}
