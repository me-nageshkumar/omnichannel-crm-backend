package me.nageshk.omnichannel.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserRepository extends MongoRepository<User, String> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByRoleId(String roleId);

    long countByRoleIdOrWorkspaceRolesRoleId(String roleId, String workspaceRoleId);

    List<User> findByCustomerId(String customerId);

    List<User> findByCustomerIdAndRoleId(String customerId, String roleId);

    List<User> findByWorkspaceRolesWorkspaceId(String workspaceId);

    List<User> findByCustomerIdIsNullOrderByName();
}
