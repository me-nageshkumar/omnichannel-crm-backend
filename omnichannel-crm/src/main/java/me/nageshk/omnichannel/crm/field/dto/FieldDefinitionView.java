package me.nageshk.omnichannel.crm.field.dto;

import java.util.List;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.field.CrmFieldDefinition;
import me.nageshk.omnichannel.crm.field.FieldOption;
import me.nageshk.omnichannel.crm.field.FieldType;

public record FieldDefinitionView(String id, TargetType entityType, String key, String label, FieldType type,
        List<FieldOption> options, boolean required, Object defaultValue, int position, boolean showInList,
        boolean showInCard, boolean active, Long version) {

    public static FieldDefinitionView of(CrmFieldDefinition d) {
        return new FieldDefinitionView(d.getId(), d.getEntityType(), d.getKey(), d.getLabel(), d.getType(),
                d.getOptions() == null ? List.of() : List.copyOf(d.getOptions()), d.isRequired(), d.getDefaultValue(),
                d.getPosition(), d.isShowInList(), d.isShowInCard(), d.isActive(), d.getVersion());
    }
}
