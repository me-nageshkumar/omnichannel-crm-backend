package me.nageshk.omnichannel.db.access;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RoleChangeRepository extends MongoRepository<RoleChange, String> {

    List<RoleChange> findByRoleIdOrderByAtDescIdDesc(String roleId);
}
