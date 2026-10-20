package me.nageshk.omnichannel.phone;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Phone numbers by brand, by number, and by device key hash. */
public interface PhoneNumberRepository extends MongoRepository<PhoneNumber, String> {

    List<PhoneNumber> findByWorkspaceIdOrderByNumber(String workspaceId);

    Optional<PhoneNumber> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<PhoneNumber> findByDeviceKeyHash(String deviceKeyHash);

    boolean existsByNumber(String number);
}
