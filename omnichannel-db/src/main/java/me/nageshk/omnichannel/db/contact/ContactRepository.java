package me.nageshk.omnichannel.db.contact;

import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/** Contacts by brand. */
public interface ContactRepository extends MongoRepository<Contact, String> {

    Optional<Contact> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<Contact> findByWorkspaceIdAndPhone(String workspaceId, String phone);

    Optional<Contact> findByWorkspaceIdAndWaIdsContaining(String workspaceId, String waId);

    /** The brand's contacts without a phone, other than those being merged away. The caller closes the stream. */
    @Query("{ 'workspaceId': ?0, 'mergingInto': { $exists: false }, 'phone': null }")
    Stream<Contact> streamPhonelessByWorkspaceId(String workspaceId);

    /**
     * The brand's contacts with a phone but no hidden {@code @lid} id, other than those being merged away. The caller
     * closes the stream.
     */
    @Query("{ 'workspaceId': ?0, 'mergingInto': { $exists: false }, 'phone': { $type: 'string' },"
            + " 'waIds': { $not: { $regex: '@lid$' } } }")
    Stream<Contact> streamLidlessByWorkspaceId(String workspaceId);

    /** The brand's contacts a merge left half done: each names its survivor in {@code mergingInto}. */
    @Query("{ 'workspaceId': ?0, 'mergingInto': { $exists: true } }")
    Stream<Contact> streamMergingByWorkspaceId(String workspaceId);
}
