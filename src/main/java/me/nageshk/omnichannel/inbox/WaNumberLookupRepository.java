package me.nageshk.omnichannel.inbox;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Cached WhatsApp number lookups. */
public interface WaNumberLookupRepository extends MongoRepository<WaNumberLookup, String> {

    Optional<WaNumberLookup> findByWorkspaceIdAndPhone(String workspaceId, String phone);
}
