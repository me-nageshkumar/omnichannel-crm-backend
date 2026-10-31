package me.nageshk.omnichannel.crm.segment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.segment.SegmentValueType;

/** The fields the builder may offer the caller, grouped (spec D C4). */
public record SegmentCatalogView(List<Group> groups) {

    public record Group(String key, String label, List<Field> fields) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Field(String key, String label, SegmentValueType type, List<String> operators, List<Option> options,
            String dependsOn, Map<String, List<Option>> optionsByParent) {}

    public record Option(String value, String label) {}
}
