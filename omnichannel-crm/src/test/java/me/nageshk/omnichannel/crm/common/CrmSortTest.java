package me.nageshk.omnichannel.crm.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.junit.jupiter.api.Test;

class CrmSortTest {

    private static final Set<String> FIELDS = Set.of("name", "creationTime");

    private static CrmSort parse(String sort, String dir, Map<String, String> errors) {
        return CrmSort.parse(sort, dir, FIELDS, "creationTime", f -> f.equals("name") ? "asc" : "desc", errors);
    }

    @Test
    void fieldCommaDirection() {
        Map<String, String> errors = new LinkedHashMap<>();
        CrmSort s = parse(" name , DESC ", null, errors);
        assertThat(errors).isEmpty();
        assertThat(s.field()).isEqualTo("name");
        assertThat(s.direction()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void separateDirWinsAndDefaultsApply() {
        Map<String, String> errors = new LinkedHashMap<>();
        assertThat(parse("name,asc", "desc", errors).direction()).isEqualTo(Sort.Direction.DESC);
        assertThat(parse("name", null, errors).direction()).isEqualTo(Sort.Direction.ASC);
        CrmSort none = parse(null, null, errors);
        assertThat(none.field()).isEqualTo("creationTime");
        assertThat(none.direction()).isEqualTo(Sort.Direction.DESC);
        assertThat(errors).isEmpty();
    }

    @Test
    void unknownFieldOrDirectionReported() {
        Map<String, String> errors = new LinkedHashMap<>();
        parse("bogus,sideways", null, errors);
        assertThat(errors).containsKeys("sort", "dir");
    }
}
