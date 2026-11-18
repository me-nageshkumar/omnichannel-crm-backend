package me.nageshk.omnichannel.db.page;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PagesAddressRepository extends MongoRepository<PagesAddress, String> {

    Optional<PagesAddress> findByWorkspaceId(String workspaceId);

    List<PagesAddress> findByCustomerId(String customerId);

    List<PagesAddress> findByBaseUrlNotNull();
}
