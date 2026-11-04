package me.nageshk.omnichannel.db.inbox;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/** Chats of WhatsApp Web numbers. */
public interface WaWebChatRepository extends MongoRepository<WaWebChat, String> {

    Optional<WaWebChat> findByPhoneNumberIdAndRemoteId(String phoneNumberId, String remoteId);

    Optional<WaWebChat> findFirstByCreatedByHistoryChatId(String historyChatId);

    List<WaWebChat> findByPhoneNumberIdAndRemoteIdIn(String phoneNumberId, java.util.Collection<String> remoteIds);

    /** A contact's chat on a number, whatever remote id it lives under ({@code @c.us} or {@code @lid}). */
    Optional<WaWebChat> findFirstByPhoneNumberIdAndContactId(String phoneNumberId, String contactId);

    /** Every chat of a brand contact, on any number and under any remote id. */
    List<WaWebChat> findAllByWorkspaceIdAndContactId(String workspaceId, String contactId);

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
