package me.nageshk.omnichannel.meta.account;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.common.api.DuplicateException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.meta.api.CloudAccountInfo;
import me.nageshk.omnichannel.meta.api.CloudAccounts;
import me.nageshk.omnichannel.meta.api.CloudNumberRequest;
import me.nageshk.omnichannel.meta.api.CloudState;
import me.nageshk.omnichannel.meta.api.ConnectionStatus;
import me.nageshk.omnichannel.meta.connection.MetaConnection;
import me.nageshk.omnichannel.meta.connection.MetaConnectionService;
import me.nageshk.omnichannel.meta.connection.WebhookSetupService;
import me.nageshk.omnichannel.meta.graph.GraphClient;
import me.nageshk.omnichannel.meta.graph.GraphException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Cloud numbers' Meta side (spec 2026-11-20 §5.2, §5.7): read from Meta, kept beside the host's phone number. */
@Service
public class CloudAccountService implements CloudAccounts {

    private static final Logger log = LoggerFactory.getLogger(CloudAccountService.class);
    private static final String LIMIT_FIELD = "whatsapp_business_manager_messaging_limit";
    /** Meta's webhook fields about a number or its account, applied to the Cloud accounts. */
    public static final java.util.Set<String> NUMBER_FIELDS_OF_CHANGES = java.util.Set.of("phone_number_quality_update",
            "phone_number_name_update", "account_update");

    private final CloudAccountRepository repository;
    private final MetaConnectionService connections;
    private final WebhookSetupService webhooks;
    private final GraphClient graph;
    private final Clock clock;

    public CloudAccountService(CloudAccountRepository repository, MetaConnectionService connections,
            WebhookSetupService webhooks, GraphClient graph, Clock clock) {
        this.repository = repository;
        this.connections = connections;
        this.webhooks = webhooks;
        this.graph = graph;
        this.clock = clock;
    }

    @Override
    public CloudAccountInfo preview(String workspaceId, CloudNumberRequest req) {
        if (req == null || blank(req.connectionId()) || blank(req.wabaId()) || blank(req.metaPhoneNumberId())) {
            throw new ValidationException("cloud", "choose the Meta connection, the WABA and the number");
        }
        MetaConnection c = connections.require(workspaceId, req.connectionId());
        if (c.getStatus() != ConnectionStatus.OK) {
            throw new ValidationException("cloud", "Meta does not accept this connection's token; check it first");
        }
        String token = connections.open(c).accessToken();
        String metaId = req.metaPhoneNumberId().strip();
        String wabaId = req.wabaId().strip();
        try {
            JsonNode n = graph.get(token, metaId, Map.of("fields", MetaConnectionService.NUMBER_FIELDS));
            boolean inWaba = false;
            for (JsonNode row : graph.get(token, wabaId + "/phone_numbers", Map.of("fields", "id")).path("data")) {
                if (metaId.equals(row.path("id").asString())) inWaba = true;
            }
            if (!inWaba) {
                throw new ValidationException("cloud", "Number " + metaId + " is not in WhatsApp account " + wabaId);
            }
            String limit = limitOf(token, wabaId);
            CloudAccount a = new CloudAccount();
            a.setWorkspaceId(workspaceId);
            a.setConnectionId(c.getId());
            a.setWabaId(wabaId);
            a.setMetaPhoneNumberId(metaId);
            a.setMessagingLimitTier(limit);
            apply(a, n);
            return info(a, c);
        } catch (GraphException e) {
            throw new ValidationException("cloud", "Meta: " + e.getMessage());
        }
    }

    @Override
    public CloudAccountInfo create(String workspaceId, String customerId, String phoneNumberId,
            CloudAccountInfo p) {
        MetaConnection c = connections.require(workspaceId, p.connectionId());
        CloudAccount a = new CloudAccount();
        a.setPhoneNumberId(phoneNumberId);
        a.setWorkspaceId(workspaceId);
        a.setCustomerId(customerId);
        a.setConnectionId(c.getId());
        a.setWabaId(p.wabaId());
        a.setMetaPhoneNumberId(p.metaPhoneNumberId());
        a.setDisplayPhoneNumber(p.displayPhoneNumber());
        a.setVerifiedName(p.verifiedName());
        a.setNameStatus(p.nameStatus());
        a.setQualityRating(p.qualityRating());
        a.setMessagingLimitTier(p.messagingLimitTier());
        a.setRegistered(p.registered());
        a.setCheckedAt(clock.instant());
        a.setCreatedAt(clock.instant());
        try {
            a = repository.insert(a);
        } catch (DuplicateKeyException e) {
            throw new DuplicateException("cloud", "This Meta number is already added");
        }
        subscribe(c, a.getWabaId());
        return info(a, c);
    }

    @Override
    public Optional<CloudAccountInfo> find(String phoneNumberId) {
        return repository.findByPhoneNumberId(phoneNumberId).map(this::info);
    }

    @Override
    public Optional<CloudAccountInfo> findByMetaPhoneNumberId(String metaPhoneNumberId) {
        return repository.findByMetaPhoneNumberId(metaPhoneNumberId).map(this::info);
    }

    @Override
    public List<CloudAccountInfo> byConnection(String connectionId) {
        return repository.findByConnectionId(connectionId).stream().map(this::info).toList();
    }

    @Override
    public CloudAccountInfo register(String phoneNumberId, String pin) {
        if (pin == null || !pin.strip().matches("\\d{6}")) {
            throw new ValidationException("pin", "the 6-digit two-step verification PIN");
        }
        CloudAccount a = repository.findByPhoneNumberId(phoneNumberId)
                .orElseThrow(() -> new NotFoundException("Cloud number not found"));
        MetaConnection c = connection(a);
        String token = connections.open(c).accessToken();
        try {
            graph.post(token, a.getMetaPhoneNumberId() + "/register",
                    Map.of("messaging_product", "whatsapp", "pin", pin.strip()));
            apply(a, graph.get(token, a.getMetaPhoneNumberId(), Map.of("fields", MetaConnectionService.NUMBER_FIELDS)));
            a.setLastError(null);
        } catch (GraphException e) {
            throw new ValidationException("pin", "Meta: " + e.getMessage());
        }
        a.setCheckedAt(clock.instant());
        return info(repository.save(a), c);
    }

    @Override
    public void delete(String phoneNumberId) {
        repository.deleteByPhoneNumberId(phoneNumberId);
    }

    /** The account of a Meta number, for routing a webhook change. */
    public Optional<CloudAccount> account(String metaPhoneNumberId) {
        return metaPhoneNumberId == null || metaPhoneNumberId.isBlank() ? Optional.empty()
                : repository.findByMetaPhoneNumberId(metaPhoneNumberId);
    }

    /**
     * A number or account change from Meta's webhook: the number named (by display number, or by id in an
     * {@code account_update}) is read from Meta again; a quality change also carries the new daily limit.
     */
    public void applyChange(MetaConnection c, String field, tools.jackson.databind.JsonNode value) {
        String display = value.path("display_phone_number").asString("").replaceAll("\\D", "");
        String metaId = value.path("phone_number").asString("");
        for (CloudAccount a : repository.findByConnectionId(c.getId())) {
            String digits = a.getDisplayPhoneNumber() == null ? "" : a.getDisplayPhoneNumber().replaceAll("\\D", "");
            boolean named = (!display.isEmpty() && display.equals(digits)) || metaId.equals(a.getMetaPhoneNumberId());
            if (!named) continue;
            String limit = value.path("max_daily_conversations_per_business").asString(
                    value.path("current_limit").asString(null));
            if (limit != null) a.setMessagingLimitTier(limit);
            try {
                String token = connections.open(c).accessToken();
                apply(a, graph.get(token, a.getMetaPhoneNumberId(), Map.of("fields", MetaConnectionService.NUMBER_FIELDS)));
            } catch (GraphException e) {
                a.setLastError("Meta: " + e.getMessage());
            }
            a.setCheckedAt(clock.instant());
            repository.save(a);
            log.info("Meta {} for number {}: {}", field, a.getMetaPhoneNumberId(), value.path("event").asString(
                    value.path("decision").asString("")));
        }
    }

    // ---- internals ----

    private void subscribe(MetaConnection c, String wabaId) {
        try {
            if (!c.getWabaIds().contains(wabaId)) {
                List<String> ids = new java.util.ArrayList<>(c.getWabaIds());
                ids.add(wabaId);
                c.setWabaIds(ids);
                connections.save(c);
            }
            webhooks.subscribeWaba(c, connections.open(c), wabaId, connections.webhookUrl(c));
        } catch (GraphException e) {
            // the number is added; the connection page offers the webhook setup again
            log.info("could not subscribe WABA {} to Meta app {}: {}", wabaId, c.getAppId(), e.getMessage());
        }
    }

    private String limitOf(String token, String wabaId) {
        try {
            return graph.get(token, wabaId, Map.of("fields", "id," + LIMIT_FIELD)).path(LIMIT_FIELD).asString(null);
        } catch (GraphException e) {
            return null;
        }
    }

    private static void apply(CloudAccount a, JsonNode n) {
        a.setDisplayPhoneNumber(n.path("display_phone_number").asString(a.getDisplayPhoneNumber()));
        a.setVerifiedName(n.path("verified_name").asString(a.getVerifiedName()));
        a.setNameStatus(n.path("name_status").asString(a.getNameStatus()));
        a.setQualityRating(n.path("quality_rating").asString(a.getQualityRating()));
        a.setPlatformType(MetaConnectionService.platformOf(n));
        a.setRegistered(MetaConnectionService.registeredOf(n));
    }

    private MetaConnection connection(CloudAccount a) {
        return connections.byId(a.getConnectionId())
                .orElseThrow(() -> new NotFoundException("The number's Meta connection is gone"));
    }

    private CloudAccountInfo info(CloudAccount a) {
        return info(a, connections.byId(a.getConnectionId()).orElse(null));
    }

    private static CloudAccountInfo info(CloudAccount a, MetaConnection c) {
        CloudState state;
        if (c == null || c.getStatus() == ConnectionStatus.INVALID) state = CloudState.TOKEN_INVALID;
        else if (!a.isRegistered()) state = CloudState.NOT_REGISTERED;
        else if (c.getLastWebhookAt() == null) state = CloudState.WAITING_FOR_WEBHOOK;
        else state = CloudState.READY;
        return new CloudAccountInfo(a.getPhoneNumberId(), a.getWorkspaceId(), a.getCustomerId(), a.getConnectionId(),
                c == null ? null : c.getName(), a.getWabaId(), a.getMetaPhoneNumberId(), a.getDisplayPhoneNumber(),
                a.getVerifiedName(), a.getNameStatus(), a.getQualityRating(), a.getMessagingLimitTier(),
                a.isRegistered(), state, c != null && c.getStatus() == ConnectionStatus.INVALID ? c.getLastError()
                        : a.getLastError());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
