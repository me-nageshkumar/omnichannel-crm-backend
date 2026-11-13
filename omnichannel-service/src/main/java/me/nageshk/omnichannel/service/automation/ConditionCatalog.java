package me.nageshk.omnichannel.service.automation;

import static me.nageshk.omnichannel.service.automation.Operator.*;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;

/** The fields a rule may test for each event, and the operators each field offers. */
public final class ConditionCatalog {

    public enum FieldType { BOOLEAN, TEXT, CHOICE, NUMBER }

    public record FieldSpec(String key, FieldType type, Set<Operator> operators) {
    }

    private static final Set<Operator> TEXT_OPS = Set.of(IS, IS_NOT, CONTAINS, NOT_CONTAINS, STARTS_WITH,
            ENDS_WITH, IS_EMPTY, IS_NOT_EMPTY);
    private static final Set<Operator> CHOICE_OPS = Set.of(IS, IS_NOT, IS_ONE_OF);

    private static FieldSpec spec(String key, FieldType type, Set<Operator> ops) {
        return new FieldSpec(key, type, ops);
    }

    private static final Map<String, FieldSpec> MESSAGE = Map.of(
            "chat.new", spec("chat.new", FieldType.BOOLEAN, Set.of(IS)),
            "chat.chatbotRunning", spec("chat.chatbotRunning", FieldType.BOOLEAN, Set.of(IS)),
            "message.text", spec("message.text", FieldType.TEXT, TEXT_OPS),
            "message.type", spec("message.type", FieldType.CHOICE, Set.of(IS_ONE_OF)),
            "number", spec("number", FieldType.CHOICE, CHOICE_OPS),
            "contact.inCrm", spec("contact.inCrm", FieldType.BOOLEAN, Set.of(IS)),
            "contact.name", spec("contact.name", FieldType.TEXT, TEXT_OPS),
            "message.ad", spec("message.ad", FieldType.BOOLEAN, Set.of(IS)),
            "message.ad.headline", spec("message.ad.headline", FieldType.TEXT, TEXT_OPS),
            "message.ad.sourceId", spec("message.ad.sourceId", FieldType.TEXT, TEXT_OPS));

    private static final Map<String, FieldSpec> LANE = Map.of(
            "pipeline", spec("pipeline", FieldType.CHOICE, CHOICE_OPS),
            "toLane", spec("toLane", FieldType.CHOICE, CHOICE_OPS),
            "fromLane", spec("fromLane", FieldType.CHOICE, CHOICE_OPS),
            "toLane.type", spec("toLane.type", FieldType.CHOICE, CHOICE_OPS),
            "deal.amount", spec("deal.amount", FieldType.NUMBER,
                    Set.of(IS, IS_NOT, GREATER_THAN, LESS_THAN, AT_LEAST, AT_MOST, BETWEEN, IS_EMPTY, IS_NOT_EMPTY)),
            "deal.owner", spec("deal.owner", FieldType.CHOICE, Set.of(IS, IS_NOT, IS_ONE_OF, IS_EMPTY, IS_NOT_EMPTY)));

    private ConditionCatalog() {
    }

    public static Optional<FieldSpec> field(AutomationEventKind event, String key) {
        if (event == null || key == null) {
            return Optional.empty();
        }
        Map<String, FieldSpec> fields = event == AutomationEventKind.MESSAGE_RECEIVED ? MESSAGE : LANE;
        return Optional.ofNullable(fields.get(key));
    }
}
