package me.nageshk.omnichannel.tenant;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WorkspaceRepository extends MongoRepository<Workspace, String> {

    List<Workspace> findByCustomerIdOrderByName(String customerId);

    Optional<Workspace> findByIdAndCustomerId(String id, String customerId);
}
