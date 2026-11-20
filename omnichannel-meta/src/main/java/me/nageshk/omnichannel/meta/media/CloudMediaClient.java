package me.nageshk.omnichannel.meta.media;

import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.meta.account.CloudAccount;
import me.nageshk.omnichannel.meta.account.CloudAccountRepository;
import me.nageshk.omnichannel.meta.api.CloudMedia;
import me.nageshk.omnichannel.meta.connection.MetaConnection;
import me.nageshk.omnichannel.meta.connection.MetaConnectionService;
import me.nageshk.omnichannel.meta.graph.GraphClient;
import me.nageshk.omnichannel.meta.graph.GraphException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Meta's media endpoints for a Cloud number: {@code GET /{media-id}} then its short-lived URL; {@code POST /{pnid}/media}. */
@Service
class CloudMediaClient implements CloudMedia {

    private final CloudAccountRepository accounts;
    private final MetaConnectionService connections;
    private final GraphClient graph;

    CloudMediaClient(CloudAccountRepository accounts, MetaConnectionService connections, GraphClient graph) {
        this.accounts = accounts;
        this.connections = connections;
        this.graph = graph;
    }

    @Override
    public Downloaded download(String phoneNumberId, String metaMediaId, long maxBytes) {
        String token = token(phoneNumberId);
        try {
            JsonNode info = graph.get(token, metaMediaId, Map.of());
            long size = info.path("file_size").asLong(-1);
            if (size > maxBytes) throw new CloudMediaException("The file is larger than allowed", true);
            String url = info.path("url").asString("");
            if (url.isBlank()) throw new CloudMediaException("Meta gave no address for the file", false);
            byte[] bytes = graph.download(token, url);
            if (bytes.length > maxBytes) throw new CloudMediaException("The file is larger than allowed", true);
            return new Downloaded(bytes, info.path("mime_type").asString(null), null);
        } catch (GraphException e) {
            throw new CloudMediaException("Meta: " + e.getMessage(), false);
        }
    }

    @Override
    public String upload(String phoneNumberId, byte[] bytes, String mimeType, String filename) {
        CloudAccount a = account(phoneNumberId);
        String token = token(a);
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("messaging_product", "whatsapp");
        parts.put("type", mimeType);
        parts.put("file", new GraphClient.FilePart(filename == null ? "file" : filename, mimeType, bytes));
        return graph.postMultipart(token, a.getMetaPhoneNumberId() + "/media", parts).path("id").asString();
    }

    private String token(String phoneNumberId) {
        return token(account(phoneNumberId));
    }

    private String token(CloudAccount a) {
        MetaConnection c = connections.byId(a.getConnectionId())
                .orElseThrow(() -> new NotFoundException("The number's Meta connection is gone"));
        return connections.open(c).accessToken();
    }

    private CloudAccount account(String phoneNumberId) {
        return accounts.findByPhoneNumberId(phoneNumberId)
                .orElseThrow(() -> new NotFoundException("Cloud number not found"));
    }
}
