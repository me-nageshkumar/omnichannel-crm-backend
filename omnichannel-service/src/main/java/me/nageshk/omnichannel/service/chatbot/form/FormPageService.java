package me.nageshk.omnichannel.service.chatbot.form;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.chatbot.ChatbotForm;
import me.nageshk.omnichannel.db.chatbot.ChatbotFormRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AskFormSettings;
import me.nageshk.omnichannel.service.chatbot.node.TaskAnswer;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import me.nageshk.omnichannel.service.chatbot.run.TaskAnswerSource;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Form pages (spec §8.4): created by an Ask with a form node, served by token, submitted once; the submission is the
 * FORM task's answer. The token is the capability; a used, expired or orphaned token is "closed".
 */
@Service
public class FormPageService implements TaskAnswerSource {

    public enum SubmitState { ANSWERED, INVALID, CLOSED }

    public record SubmitResult(SubmitState state, Map<String, String> errors, String html) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChatbotFormRepository forms;
    private final ChatbotRunRepository runs;
    private final ChatbotRunService runService;
    private final FormPageRenderer renderer;
    private final me.nageshk.omnichannel.service.page.PreviewCardRenderer cards;
    private final MongoTemplate mongo;
    private final ObjectMapper mapper;
    private final OmnichannelProperties props;
    private final Clock clock;

    public FormPageService(ChatbotFormRepository forms, ChatbotRunRepository runs, @Lazy ChatbotRunService runService,
            FormPageRenderer renderer, me.nageshk.omnichannel.service.page.PreviewCardRenderer cards, MongoTemplate mongo,
            ObjectMapper mapper, OmnichannelProperties props, Clock clock) {
        this.cards = cards;
        this.forms = forms;
        this.runs = runs;
        this.runService = runService;
        this.renderer = renderer;
        this.mongo = mongo;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public TaskKind kind() {
        return TaskKind.FORM;
    }

    /** Stores the form for the run; {@code rendered} has its texts already rendered with the run's variables. */
    public ChatbotForm create(ChatbotRun run, FlowNode node, AskFormSettings rendered, Instant expiresAt) {
        ChatbotForm f = new ChatbotForm();
        f.setWorkspaceId(run.getWorkspaceId());
        f.setRunId(run.getId());
        f.setNodeId(node.id());
        f.setToken(newToken());
        f.setDefinition(mapper.convertValue(rendered, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        f.setCreatedAt(clock.instant());
        f.setExpiresAt(expiresAt);
        return forms.insert(f);
    }

    public String url(ChatbotForm f) {
        return props.pages().baseUrl() + "/f/" + f.getToken();
    }

    public Optional<ChatbotForm> byToken(String token) {
        return token == null || token.isBlank() ? Optional.empty() : forms.findByToken(token);
    }

    /** True when the form can still be filled in: not submitted, not expired, and its run still waits on it. */
    public boolean open(ChatbotForm f) {
        if (f.getSubmittedAt() != null) return false;
        if (f.getExpiresAt() != null && !clock.instant().isBefore(f.getExpiresAt())) return false;
        return runs.findById(f.getRunId()).filter(r -> r.getStatus() == ChatbotRunStatus.WAITING && r.getWaiting() != null
                && r.getWaiting().kind() == TaskKind.FORM && f.getToken().equals(r.getWaiting().key())).isPresent();
    }

    public AskFormSettings settings(ChatbotForm f) {
        return mapper.convertValue(f.getDefinition(), AskFormSettings.class);
    }

    public String html(ChatbotForm f) {
        return renderer.form(settings(f), "/f/" + f.getToken(), Map.of(), Map.of(), url(f));
    }

    /**
     * The link card's image: the message pages' card drawn from the form's title, intro and button (the card renderer
     * takes an interactive message, so the form is described as one).
     */
    public byte[] previewImage(ChatbotForm f) {
        AskFormSettings s = settings(f);
        String intro = s.intro() == null || s.intro().isBlank() ? FormPageRenderer.OPEN_THE_FORM : s.intro();
        String button = s.submitText() == null || s.submitText().isBlank() ? "Submit" : s.submitText();
        var interactive = new me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive("button",
                new me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.Header("text", s.title(), null, null, null),
                new me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.Body(intro), null,
                new me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.Action(List.of(
                        new me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.ActionButton("reply",
                                new me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.Reply("submit", button))),
                        null, null, null, null));
        var message = new me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage(
                me.nageshk.omnichannel.common.channel.whatsapp.message.MessagingProduct.WHATSAPP_WEB, "individual", "0",
                me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage.INTERACTIVE, null, interactive,
                null, null, null, null, null, null);
        return cards.draw(message, Optional.empty());
    }

    public String closedHtml() {
        return renderer.closed();
    }

    public String notFoundHtml() {
        return renderer.notFound();
    }

    /** Validates, stores the answers once, and answers the run's FORM task. */
    public SubmitResult submit(ChatbotForm f, Map<String, String> values) {
        if (!open(f)) return new SubmitResult(SubmitState.CLOSED, Map.of(), renderer.closed());
        AskFormSettings s = settings(f);
        Map<String, String> errors = FormAnswers.validate(s, values);
        if (!errors.isEmpty()) {
            return new SubmitResult(SubmitState.INVALID, errors, renderer.form(s, "/f/" + f.getToken(), values, errors));
        }
        Map<String, Object> answers = FormAnswers.clean(s, values);
        Instant now = clock.instant();
        ChatbotForm stored = mongo.findAndModify(new Query(Criteria.where("_id").is(f.getId()).and("submittedAt").is(null)),
                new Update().set("answers", answers).set("submittedAt", now), FindAndModifyOptions.options().returnNew(true),
                ChatbotForm.class);
        if (stored == null) return new SubmitResult(SubmitState.CLOSED, Map.of(), renderer.closed());
        Map<String, Object> payload = new java.util.LinkedHashMap<>(answers);
        payload.put("submittedAt", now.toString());
        runService.answer(TaskAnswer.form(f.getToken(), payload));
        return new SubmitResult(SubmitState.ANSWERED, Map.of(), renderer.thanks(s));
    }

    static String newToken() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
