package me.nageshk.omnichannel.db.billing;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface InvoiceRepository extends MongoRepository<Invoice, String> {

    List<Invoice> findByCustomerIdOrderByIssuedAtDescIdDesc(String customerId);
}
