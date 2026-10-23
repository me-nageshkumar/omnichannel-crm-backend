package me.nageshk.omnichannel.crm.common;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.springframework.data.domain.Sort;

/**
 * The sort of a CRM search: {@code sort} is a whitelisted field, optionally {@code "field,asc|desc"}; a separate
 * {@code dir} wins over that suffix. Shared by the contact, company and deal searches.
 */
public record CrmSort(String field, Sort.Direction direction) {

    /**
     * Parses the request's sort, reporting an unknown field under {@code sort} and a bad direction under {@code dir}.
     *
     * @param defaultField when {@code sort} is blank
     * @param defaultDir the direction ("asc"/"desc") of a field when none is given
     */
    public static CrmSort parse(String sort, String dir, Set<String> allowed, String defaultField,
            Function<String, String> defaultDir, Map<String, String> errors) {
        String field = sort == null || sort.isBlank() ? defaultField : sort.trim();
        String direction = dir == null || dir.isBlank() ? null : dir.trim().toLowerCase(Locale.ROOT);
        int comma = field.indexOf(',');
        if (comma >= 0) {
            if (direction == null) direction = field.substring(comma + 1).trim().toLowerCase(Locale.ROOT);
            field = field.substring(0, comma).trim();
        }
        if (!allowed.contains(field)) {
            errors.put("sort", "must be one of " + String.join(", ", new TreeSet<>(allowed)));
            field = defaultField;
        }
        if (direction == null || direction.isEmpty()) direction = defaultDir.apply(field);
        if (!direction.equals("asc") && !direction.equals("desc")) {
            errors.put("dir", "must be asc or desc");
            direction = "desc";
        }
        return new CrmSort(field, direction.equals("asc") ? Sort.Direction.ASC : Sort.Direction.DESC);
    }
}
