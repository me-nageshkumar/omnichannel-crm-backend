package me.nageshk.omnichannel.crm.segment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.common.PicklistItem;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.contact.OriginKind;
import me.nageshk.omnichannel.crm.deal.DealStatus;
import me.nageshk.omnichannel.crm.field.CrmFieldDefinition;
import me.nageshk.omnichannel.crm.field.FieldDefinitionService;
import me.nageshk.omnichannel.crm.field.FieldOption;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.pipeline.CrmPipelineService;
import me.nageshk.omnichannel.crm.platform.ChannelAccount;
import me.nageshk.omnichannel.crm.platform.ChannelAttribute;
import me.nageshk.omnichannel.crm.platform.ChannelContactQuery;
import me.nageshk.omnichannel.crm.platform.ChannelLabel;
import me.nageshk.omnichannel.crm.segment.dto.SegmentCatalogView;
import me.nageshk.omnichannel.crm.segment.dto.SegmentCatalogView.Field;
import me.nageshk.omnichannel.crm.segment.dto.SegmentCatalogView.Group;
import me.nageshk.omnichannel.crm.segment.dto.SegmentCatalogView.Option;
import me.nageshk.omnichannel.crm.settings.CrmSettings;
import org.springframework.stereotype.Component;

/** The builder's field catalogue for one caller (spec D C4, C13): built-ins, their custom fields, labels, attributes. */
@Component
public class SegmentCatalog {

    /** Operators in the order the builder lists them. */
    static final List<String> OPERATOR_ORDER = List.of("is", "isNot", "in", "notIn", "contains", "notContains",
            "startsWith", "eq", "neq", "lt", "lte", "gt", "gte", "between", "before", "after", "withinLastDays",
            "moreThanDaysAgo", "has", "hasNot", "hasAny", "hasAll", "hasNone", "isSet", "isNotSet", "isEmpty", "isNotEmpty");

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("contact.lifecycleStatus", "Lifecycle");
        LABELS.put("contact.ownerId", "Owner");
        LABELS.put("contact.source", "Source");
        LABELS.put("contact.origin.kind", "Origin");
        LABELS.put("contact.companyId", "Company");
        LABELS.put("contact.channelAccountId", "Channel account");
        LABELS.put("contact.email", "Email");
        LABELS.put("contact.jobTitle", "Job title");
        LABELS.put("contact.creationTime", "Created");
        LABELS.put("contact.lastActivityAt", "Last activity");
        LABELS.put("contact.nextTaskDueAt", "Next task due");
        LABELS.put("contact.openDealCount", "Open deals");
        LABELS.put("contact.openDealAmount", "Open deal value");
        LABELS.put("deal.pipelineId", "Pipeline");
        LABELS.put("deal.stageId", "Stage");
        LABELS.put("deal.status", "Status");
        LABELS.put("deal.amount", "Amount");
        LABELS.put("deal.currency", "Currency");
        LABELS.put("deal.ownerId", "Owner");
        LABELS.put("deal.source", "Source");
        LABELS.put("deal.lostReason", "Lost reason");
        LABELS.put("deal.daysInStage", "Days in stage");
        LABELS.put("deal.expectedCloseDate", "Expected close");
        LABELS.put("deal.closedAt", "Closed");
        LABELS.put("deal.creationTime", "Created");
        LABELS.put("deal.rotting", "Rotting");
        LABELS.put("channel.label", "Label");
        LABELS.put("channel.lastMessageAt", "Last message");
        LABELS.put("channel.source", "Contact source");
        LABELS.put("channel.doNotContact", "Do not contact");
    }

    private final FieldDefinitionService fields;
    private final CrmPipelineService pipelines;
    private final ChannelContactQuery channel;

    public SegmentCatalog(FieldDefinitionService fields, CrmPipelineService pipelines, ChannelContactQuery channel) {
        this.fields = fields;
        this.pipelines = pipelines;
        this.channel = channel;
    }

    public SegmentCatalogView build(CrmContext ctx) {
        CrmSettings s = ctx.settings();
        List<ChannelAccount> accounts = channel.accounts(ctx.workspaceId());
        List<CrmPipeline> visible = pipelines.list(ctx).stream().filter(CrmPipeline::isActive).toList();

        List<Field> contact = new ArrayList<>();
        contact.add(field("contact.lifecycleStatus", SegmentValueType.PICK, picklist(s.getLifecycleStatuses()), null, null));
        contact.add(field("contact.ownerId", SegmentValueType.USER, null, null, null));
        contact.add(field("contact.source", SegmentValueType.TEXT, null, null, null));
        contact.add(field("contact.origin.kind", SegmentValueType.ENUM, enumOptions(OriginKind.values()), null, null));
        contact.add(field("contact.companyId", SegmentValueType.COMPANY, null, null, null));
        contact.add(field("contact.channelAccountId", SegmentValueType.PICK,
                accounts.stream().map(a -> new Option(a.id(), a.label())).toList(), null, null));
        contact.add(field("contact.email", SegmentValueType.TEXT, null, null, null));
        contact.add(field("contact.jobTitle", SegmentValueType.TEXT, null, null, null));
        contact.add(field("contact.creationTime", SegmentValueType.DATE, null, null, null));
        contact.add(field("contact.lastActivityAt", SegmentValueType.DATE, null, null, null));
        contact.add(field("contact.nextTaskDueAt", SegmentValueType.DATE, null, null, null));
        contact.add(field("contact.openDealCount", SegmentValueType.NUMBER, null, null, null));
        contact.add(field("contact.openDealAmount", SegmentValueType.MONEY, null, null, null));
        for (CrmFieldDefinition d : fields.list(ctx, TargetType.CONTACT, false)) contact.add(custom(SegmentFields.CONTACT_CUSTOM, d));

        List<Field> deal = new ArrayList<>();
        Map<String, List<Option>> stagesByPipeline = new LinkedHashMap<>();
        for (CrmPipeline p : visible) {
            stagesByPipeline.put(p.getId(), p.getStages().stream().map(st -> new Option(st.getId(), st.getName())).toList());
        }
        deal.add(field("deal.pipelineId", SegmentValueType.PICK,
                visible.stream().map(p -> new Option(p.getId(), p.getName())).toList(), null, null));
        deal.add(field("deal.stageId", SegmentValueType.PICK, null, "deal.pipelineId", stagesByPipeline));
        deal.add(field("deal.status", SegmentValueType.ENUM, enumOptions(DealStatus.values()), null, null));
        deal.add(field("deal.amount", SegmentValueType.MONEY, null, null, null));
        deal.add(field("deal.currency", SegmentValueType.TEXT, null, null, null));
        deal.add(field("deal.ownerId", SegmentValueType.USER, null, null, null));
        deal.add(field("deal.source", SegmentValueType.PICK, picklist(s.getDealSources()), null, null));
        deal.add(field("deal.lostReason", SegmentValueType.PICK, picklist(s.getLostReasons()), null, null));
        deal.add(field("deal.daysInStage", SegmentValueType.NUMBER, null, null, null));
        deal.add(field("deal.expectedCloseDate", SegmentValueType.DATE, null, null, null));
        deal.add(field("deal.closedAt", SegmentValueType.DATE, null, null, null));
        deal.add(field("deal.creationTime", SegmentValueType.DATE, null, null, null));
        deal.add(field("deal.rotting", SegmentValueType.BOOLEAN, null, null, null));
        for (CrmFieldDefinition d : fields.list(ctx, TargetType.DEAL, false)) deal.add(custom(SegmentFields.DEAL_CUSTOM, d));

        List<Field> chan = new ArrayList<>();
        chan.add(field("channel.label", SegmentValueType.LABEL,
                channel.labels(ctx.workspaceId()).stream().map(l -> new Option(l.id(), l.name())).toList(), null, null));
        for (ChannelAttribute a : channel.attributes(ctx.workspaceId())) {
            chan.add(new Field(SegmentFields.CHANNEL_ATTRIBUTE + a.id(), a.name(), SegmentValueType.TEXT,
                    operators(SegmentValueType.TEXT), null, null, null));
        }
        chan.add(field("channel.lastMessageAt", SegmentValueType.DATE, null, null, null));
        chan.add(field("channel.source", SegmentValueType.ENUM, List.of(new Option("CHAT", "Chat"),
                new Option("IMPORT", "Import"), new Option("MANUAL", "Manual")), null, null));
        chan.add(field("channel.doNotContact", SegmentValueType.BOOLEAN, null, null, null));

        return new SegmentCatalogView(List.of(new Group("contact", "CRM contact", contact),
                new Group("deal", "Has a deal where", deal), new Group("channel", "Channel contact", chan)));
    }

    private static Field field(String key, SegmentValueType type, List<Option> options, String dependsOn,
            Map<String, List<Option>> optionsByParent) {
        return new Field(key, LABELS.get(key), type, operators(type), options, dependsOn, optionsByParent);
    }

    private static Field custom(String prefix, CrmFieldDefinition d) {
        SegmentValueType type = SegmentFields.typeOfCustom(d.getType());
        List<Option> options = type == SegmentValueType.PICK || type == SegmentValueType.MULTI
                ? d.getOptions().stream().filter(FieldOption::active).map(o -> new Option(o.key(), o.label())).toList()
                : null;
        return new Field(prefix + d.getKey(), d.getLabel(), type, operators(type), options, null, null);
    }

    static List<String> operators(SegmentValueType type) {
        Set<String> ops = SegmentFields.operators(type);
        return OPERATOR_ORDER.stream().filter(ops::contains).toList();
    }

    private static List<Option> picklist(List<PicklistItem> items) {
        return items == null ? List.of() : items.stream().filter(PicklistItem::active)
                .map(i -> new Option(i.key(), i.label())).toList();
    }

    private static List<Option> enumOptions(Enum<?>[] values) {
        List<Option> out = new ArrayList<>();
        for (Enum<?> e : values) {
            String name = e.name().toLowerCase().replace('_', ' ');
            out.add(new Option(e.name(), Character.toUpperCase(name.charAt(0)) + name.substring(1)));
        }
        return out;
    }
}
