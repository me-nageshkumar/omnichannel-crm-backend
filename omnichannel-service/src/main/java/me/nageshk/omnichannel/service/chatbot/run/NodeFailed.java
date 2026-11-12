package me.nageshk.omnichannel.service.chatbot.run;

/** A node could not do its work; the run fails with this reason (spec §5.2). */
public class NodeFailed extends RuntimeException {
    public NodeFailed(String message) {
        super(message);
    }
}
