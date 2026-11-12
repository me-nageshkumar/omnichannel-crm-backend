package me.nageshk.omnichannel.service.chatbot.node;

import java.time.Instant;
import java.util.Map;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettings;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotText;

/** What an executor sees while a node runs: the run, its flow and settings, the person, and the variables. */
public class RunContext {

    private final ChatbotRun run;
    private final Chatbot chatbot;
    private final Flow flow;
    private final ChatbotSettings settings;
    private final Instant now;
    private WaWebChat chat;
    private Contact contact;
    private Object sender;

    public RunContext(ChatbotRun run, Chatbot chatbot, Flow flow, ChatbotSettings settings, WaWebChat chat,
            Contact contact, Instant now) {
        this.run = run;
        this.chatbot = chatbot;
        this.flow = flow;
        this.settings = settings;
        this.chat = chat;
        this.contact = contact;
        this.now = now;
    }

    public ChatbotRun run() { return run; }
    public Chatbot chatbot() { return chatbot; }
    public Flow flow() { return flow; }
    public ChatbotSettings settings() { return settings; }
    public WaWebChat chat() { return chat; }
    public void chat(WaWebChat chat) { this.chat = chat; }
    public Contact contact() { return contact; }
    public void contact(Contact contact) { this.contact = contact; }
    public Instant now() { return now; }
    public boolean test() { return run.isTest(); }
    public String workspaceId() { return run.getWorkspaceId(); }

    public Map<String, Object> vars() {
        return run.getVariables();
    }

    public String render(String template) {
        return ChatbotText.render(template, vars());
    }

    public String renderJson(String template) {
        return ChatbotText.renderJson(template, vars());
    }

    /** The sender the runner hands in (Task 7); typed loosely here so the contract compiles before it exists. */
    @SuppressWarnings("unchecked")
    public <T> T sender(Class<T> type) {
        return (T) sender;
    }

    public void sender(Object sender) {
        this.sender = sender;
    }
}
