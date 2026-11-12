package me.nageshk.omnichannel.service.chatbot.flow;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/** The node types a flow may hold (spec §4.2); {@link #code()} is the wire and canvas form. */
public enum NodeType {
    SEND_MESSAGE("send_message"), SEND_TEMPLATE("send_template"), ASK_QUESTION("ask_question"),
    ASK_FORM("ask_form"), CONDITION("condition"), UPDATE_CONTACT("update_contact"),
    DO_NOT_CONTACT("do_not_contact"), ASSIGN_CHAT("assign_chat"), ADD_TO_CRM("add_to_crm"),
    HTTP_REQUEST("http_request"), WAIT("wait"), JUMP("jump");

    private final String code;

    NodeType(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public static Optional<NodeType> of(String code) {
        return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst();
    }
}
