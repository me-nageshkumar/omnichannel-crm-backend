package me.nageshk.omnichannel.contact;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Contact notes by brand and contact. */
public interface ContactNoteRepository extends MongoRepository<ContactNote, String> {

    List<ContactNote> findByWorkspaceIdAndContactIdOrderByCreationTimeDesc(String workspaceId, String contactId);

    Optional<ContactNote> findByIdAndWorkspaceIdAndContactId(String id, String workspaceId, String contactId);

    void deleteByWorkspaceIdAndContactId(String workspaceId, String contactId);
}
