package me.nageshk.omnichannel.meta.send;

import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;
import me.nageshk.omnichannel.common.channel.whatsapp.message.MessagingProduct;
import me.nageshk.omnichannel.meta.account.CloudAccount;
import me.nageshk.omnichannel.meta.account.CloudAccountRepository;
import me.nageshk.omnichannel.meta.api.CloudApi;
import me.nageshk.omnichannel.meta.api.CloudSendException;
import me.nageshk.omnichannel.meta.connection.MetaConnection;
import me.nageshk.omnichannel.meta.connection.MetaConnectionService;
import me.nageshk.omnichannel.meta.graph.GraphClient;
import me.nageshk.omnichannel.meta.graph.GraphException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Sends through Meta with the number's connection token; maps Meta's refusals to {@link CloudSendException}. */
@Service
class CloudApiClient implements CloudApi {

    private static final Logger log = LoggerFactory.getLogger(CloudApiClient.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    private final CloudAccountRepository accounts;
    private final MetaConnectionService connections;
    private final GraphClient graph;
    private final ObjectMapper mapper;

    CloudApiClient(CloudAccountRepository accounts, MetaConnectionService connections, GraphClient graph,
            ObjectMapper mapper) {
        this.accounts = accounts;
        this.connections = connections;
        this.graph = graph;
        this.mapper = mapper;
    }

    @Override
    public String send(String phoneNumberId, CloudMessage message, String recipientUserId) {
        CloudAccount a = account(phoneNumberId);
        MetaConnection c = connection(a);
        String token = connections.open(c).accessToken();
        try {
            JsonNode res = graph.post(token, a.getMetaPhoneNumberId() + "/messages", body(mapper, message,
                    recipientUserId));
            String id = res.path("messages").path(0).path("id").asString("");
            if (id.isBlank()) throw new CloudSendException(CloudSendException.Kind.UNKNOWN, null,
                    "Meta answered without a message id", null, null);
            return id;
        } catch (GraphException e) {
            if (e.kind() == GraphException.Kind.AUTH) connections.markInvalid(c.getId(), e.getMessage());
            throw of(e);
        }
    }

    @Override
    public void markRead(String phoneNumberId, String waMessageId) {
        try {
            CloudAccount a = account(phoneNumberId);
            String token = connections.open(connection(a)).accessToken();
            graph.post(token, a.getMetaPhoneNumberId() + "/messages",
                    Map.of("messaging_product", MessagingProduct.WHATSAPP, "status", "read", "message_id", waMessageId));
        } catch (RuntimeException e) {
            log.info("could not mark {} read at Meta: {}", waMessageId, e.getMessage());
        }
    }

    /** The request body: Meta's {@code /messages} object, product {@code whatsapp}, and the user-id recipient. */
    static Map<String, Object> body(ObjectMapper mapper, CloudMessage message, String recipientUserId) {
        Map<String, Object> m = mapper.convertValue(message, MAP);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messaging_product", MessagingProduct.WHATSAPP);
        out.put("recipient_type", m.getOrDefault("recipient_type", "individual"));
        if (recipientUserId != null && !recipientUserId.isBlank()) out.put("recipient", recipientUserId);
        else out.put("to", m.get("to"));
        m.forEach((k, v) -> {
            if (!k.equals("messaging_product") && !k.equals("recipient_type") && !k.equals("to")) out.put(k, v);
        });
        return out;
    }

    static CloudSendException of(GraphException e) {
        CloudSendException.Kind kind = switch (e.kind()) {
            case AUTH -> CloudSendException.Kind.TOKEN_REFUSED;
            case WINDOW_CLOSED -> CloudSendException.Kind.WINDOW_CLOSED;
            case RATE_LIMIT -> CloudSendException.Kind.RATE_LIMITED;
            case BAD_REQUEST, NOT_FOUND -> CloudSendException.Kind.REFUSED;
            case UNAVAILABLE, TIMEOUT -> e.refused() ? CloudSendException.Kind.REFUSED : CloudSendException.Kind.UNKNOWN;
        };
        String title = e.title() == null ? e.getMessage() : e.title();
        return new CloudSendException(kind, e.metaCode(), title, e.details(), e.retryAfter());
    }

    private CloudAccount account(String phoneNumberId) {
        return accounts.findByPhoneNumberId(phoneNumberId)
                .orElseThrow(() -> new NotFoundException("Cloud number not found"));
    }

    private MetaConnection connection(CloudAccount a) {
        return connections.byId(a.getConnectionId())
                .orElseThrow(() -> new NotFoundException("The number's Meta connection is gone"));
    }
}
