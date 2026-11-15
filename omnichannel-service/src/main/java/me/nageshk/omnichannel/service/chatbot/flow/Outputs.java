package me.nageshk.omnichannel.service.chatbot.flow;

/** The output ids edges leave from; the console's handle ids are these exact strings. */
public final class Outputs {
    public static final String NEXT = "next";
    public static final String OTHER = "other";
    public static final String NO_ANSWER = "noAnswer";
    public static final String OTHERWISE = "otherwise";
    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";
    public static final String EXIT_WORD = "exitWord";
    public static final String EXIT_CONDITION = "exitCondition";
    public static final String MAX_TURNS = "maxTurns";

    private Outputs() {}

    /** The output a reply button or list row leads to. */
    public static String reply(String id) {
        return "reply:" + id;
    }

    /** The output a condition case leads to. */
    public static String caseOf(String id) {
        return "case:" + id;
    }

    /** The output an AI node's outcome leads to. */
    public static String outcomeOf(String id) {
        return "outcome:" + id;
    }
}
