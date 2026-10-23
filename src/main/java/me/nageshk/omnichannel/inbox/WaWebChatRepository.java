package me.nageshk.omnichannel.inbox;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/** Chats of WhatsApp Web numbers. */
public interface WaWebChatRepository extends MongoRepository<WaWebChat, String> {

    Optional<WaWebChat> findByPhoneNumberIdAndRemoteId(String phoneNumberId, String remoteId);

    /** A contact's chat on a number, whatever remote id it lives under ({@code @c.us} or {@code @lid}). */
    Optional<WaWebChat> findFirstByPhoneNumberIdAndContactId(String phoneNumberId, String contactId);

    Optional<WaWebChat> findByIdAndWorkspaceId(String id, String workspaceId);

    /** Only the {@code id} of each chat is loaded. */
    @Query(value = "{ 'phoneNumberId': ?0 }", fields = "{ '_id': 1 }")
    List<WaWebChat> findIdsByPhoneNumberId(String phoneNumberId);

    /** A team's queue: open chats routed to the team that nobody has taken, oldest message first. */
    List<WaWebChat> findByWorkspaceIdAndAssignedTeamIdAndAssignedUserIdIsNullAndStatusOrderByLastMessageAtAsc(
            String workspaceId, String assignedTeamId, ChatStatus status);

    List<WaWebChat> findByPhoneNumberIdAndStatus(String phoneNumberId, ChatStatus status);

    void deleteByPhoneNumberId(String phoneNumberId);
}
