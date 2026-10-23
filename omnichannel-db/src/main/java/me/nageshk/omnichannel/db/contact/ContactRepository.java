package me.nageshk.omnichannel.db.contact;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Contacts by brand. */
public interface ContactRepository extends MongoRepository<Contact, String> {

    Optional<Contact> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<Contact> findByWorkspaceIdAndPhone(String workspaceId, String phone);

    Optional<Contact> findByWorkspaceIdAndWaId(String workspaceId, String waId);
}
