package me.nageshk.omnichannel.access;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RoleRepository extends MongoRepository<Role, String> {

    Optional<Role> findByName(String name);

    boolean existsByName(String name);
}
