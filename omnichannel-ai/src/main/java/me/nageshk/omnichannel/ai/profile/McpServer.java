package me.nageshk.omnichannel.ai.profile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.common.secret.SealedSecret;

/** An MCP server a profile may use; the secret is sealed and write-only. */
public class McpServer {

    private String id;
    private String label;
    private String url;
    private ConnectBy connectBy;
    private McpAuth auth = McpAuth.NONE;
    private String headerName;
    private SealedSecret secret;
    private String secretHint;
    private Instant secretSetAt;
    private List<String> allowedTools = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public ConnectBy getConnectBy() { return connectBy; }
    public void setConnectBy(ConnectBy connectBy) { this.connectBy = connectBy; }
    public McpAuth getAuth() { return auth; }
    public void setAuth(McpAuth auth) { this.auth = auth; }
    public String getHeaderName() { return headerName; }
    public void setHeaderName(String headerName) { this.headerName = headerName; }
    public SealedSecret getSecret() { return secret; }
    public void setSecret(SealedSecret secret) { this.secret = secret; }
    public String getSecretHint() { return secretHint; }
    public void setSecretHint(String secretHint) { this.secretHint = secretHint; }
    public Instant getSecretSetAt() { return secretSetAt; }
    public void setSecretSetAt(Instant secretSetAt) { this.secretSetAt = secretSetAt; }
    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
}
