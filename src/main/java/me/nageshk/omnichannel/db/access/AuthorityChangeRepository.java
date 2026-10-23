package me.nageshk.omnichannel.db.access;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for {@link AuthorityChange}, the authority audit trail. */
public interface AuthorityChangeRepository extends MongoRepository<AuthorityChange, String> {

    List<AuthorityChange> findByCodeOrderByAtDescIdDesc(String code);

    boolean existsByCode(String code);
}
