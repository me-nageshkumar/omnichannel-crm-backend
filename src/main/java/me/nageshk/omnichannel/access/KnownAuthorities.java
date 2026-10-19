package me.nageshk.omnichannel.access;

import java.util.HashSet;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Singleton record of the authority codes the system has already seen, so removals by the Super Admin stick. */
@Document("system_state")
public class KnownAuthorities {

    public static final String ID = "known-authorities";

    @Id
    private String id = ID;

    private Set<String> codes = new HashSet<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Set<String> getCodes() { return codes; }
    public void setCodes(Set<String> codes) { this.codes = codes; }
}
