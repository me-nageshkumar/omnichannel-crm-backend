package me.nageshk.omnichannel.meta.connection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.meta.api.WebhookSetup;
import me.nageshk.omnichannel.meta.graph.GraphClient;
import me.nageshk.omnichannel.meta.graph.GraphException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Points a connection's Meta app at our webhook without ever overwriting someone else's callback (spec 2026-11-20
 * §5.7): no callback or ours → the app's callback is set ({@code AUTO}); another callback → each WABA is pointed at us
 * with Meta's override ({@code OVERRIDE}); any failure → the admin pastes it by hand ({@code MANUAL}).
 */
@Service
public class WebhookSetupService {

    private static final Logger log = LoggerFactory.getLogger(WebhookSetupService.class);
    public static final String OBJECT = "whatsapp_business_account";
    public static final List<String> FIELDS = List.of("messages", "message_template_status_update",
            "phone_number_quality_update", "phone_number_name_update", "account_update");

    private final GraphClient graph;

    public WebhookSetupService(GraphClient graph) {
        this.graph = graph;
    }

    /** Sets {@code webhookSetup} and {@code webhookError} on {@code c}; the caller saves it. */
    public WebhookSetup setUp(MetaConnection c, MetaConnectionService.Secrets s, String callbackUrl) {
        String appToken = s.appToken(c.getAppId());
        try {
            String current = currentCallback(appToken, c.getAppId());
            WebhookSetup result;
            if (current == null || current.equals(callbackUrl)) {
                if (current == null) {
                    Map<String, String> form = new LinkedHashMap<>();
                    form.put("object", OBJECT);
                    form.put("callback_url", callbackUrl);
                    form.put("verify_token", c.getVerifyToken());
                    form.put("fields", String.join(",", FIELDS));
                    graph.postForm(appToken, c.getAppId() + "/subscriptions", form);
                }
                for (String waba : c.getWabaIds()) graph.post(s.accessToken(), waba + "/subscribed_apps", null);
                result = WebhookSetup.AUTO;
            } else {
                for (String waba : c.getWabaIds()) {
                    graph.post(s.accessToken(), waba + "/subscribed_apps",
                            Map.of("override_callback_uri", callbackUrl, "verify_token", c.getVerifyToken()));
                }
                result = WebhookSetup.OVERRIDE;
            }
            c.setWebhookSetup(result);
            c.setWebhookError(null);
            return result;
        } catch (GraphException e) {
            log.info("webhook setup of Meta app {} failed: {}", c.getAppId(), e.getMessage());
            c.setWebhookSetup(WebhookSetup.MANUAL);
            c.setWebhookError(e.getMessage());
            return WebhookSetup.MANUAL;
        }
    }

    /** Subscribes one WABA to the app (a number added later may sit in a WABA the setup has not seen). */
    public void subscribeWaba(MetaConnection c, MetaConnectionService.Secrets s, String wabaId, String callbackUrl) {
        Object body = c.getWebhookSetup() == WebhookSetup.OVERRIDE
                ? Map.of("override_callback_uri", callbackUrl, "verify_token", c.getVerifyToken())
                : null;
        graph.post(s.accessToken(), wabaId + "/subscribed_apps", body);
    }

    /** The app's current callback for WhatsApp business accounts, or null. */
    private String currentCallback(String appToken, String appId) {
        JsonNode list = graph.get(appToken, appId + "/subscriptions", Map.of());
        for (JsonNode sub : list.path("data")) {
            if (!OBJECT.equals(sub.path("object").asString(""))) continue;
            String url = sub.path("callback_url").asString("");
            return url.isBlank() ? null : url;
        }
        return null;
    }
}
