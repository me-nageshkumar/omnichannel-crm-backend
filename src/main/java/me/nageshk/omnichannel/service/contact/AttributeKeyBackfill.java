package me.nageshk.omnichannel.service.contact;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/** Gives contact attributes saved before keys existed a key, per brand in _id order. Idempotent; every profile. */
@Component
@Order(7)
public class AttributeKeyBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AttributeKeyBackfill.class);

    private final ContactAttributeRepository attributes;

    public AttributeKeyBackfill(ContactAttributeRepository attributes) {
        this.attributes = attributes;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ContactAttribute> all = attributes.findAll(Sort.by("id"));
        Map<String, Set<String>> taken = new HashMap<>();
        for (ContactAttribute a : all) {
            if (a.getKey() != null) taken.computeIfAbsent(a.getWorkspaceId(), w -> new HashSet<>()).add(a.getKey());
        }
        int n = 0;
        for (ContactAttribute a : all) {
            if (a.getKey() != null) continue;
            Set<String> keys = taken.computeIfAbsent(a.getWorkspaceId(), w -> new HashSet<>());
            a.setKey(AttributeKeys.generate(a.getName(), keys));
            keys.add(a.getKey());
            attributes.save(a);
            n++;
        }
        if (n > 0) log.info("gave {} contact attribute(s) a key", n);
    }
}
