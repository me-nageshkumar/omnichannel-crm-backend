package me.nageshk.omnichannel.db.automation;

import java.math.BigDecimal;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** The facts of an event, as the rule conditions see them. */
public record EventData(ChatData chat, MessageData message, NumberData number, ContactData contact, DealData deal,
        Ref pipeline, LaneData fromLane, LaneData toLane) {

    public record Ref(String id, String name) {
    }

    public record ChatData(String id, boolean isNew, ChatStatus status, Ref assignedUser, Ref assignedTeam) {
    }

    /** {@code type} is the message kind. */
    public record MessageData(String id, String text, String type) {
    }

    public record NumberData(String id, String number, String label) {
    }

    public record ContactData(String id, String name, String phone, boolean inCrm, boolean doNotContact) {
    }

    public record DealData(String id, String title, String status,
            @Field(targetType = FieldType.DECIMAL128) BigDecimal amount, String currency, Ref owner,
            String lostReason) {
    }

    public record LaneData(String id, String name, String type) {
    }
}
