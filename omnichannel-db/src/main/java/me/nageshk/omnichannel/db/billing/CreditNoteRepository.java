package me.nageshk.omnichannel.db.billing;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CreditNoteRepository extends MongoRepository<CreditNote, String> {

    List<CreditNote> findByCustomerIdOrderByIssuedAtDescIdDesc(String customerId);
}
