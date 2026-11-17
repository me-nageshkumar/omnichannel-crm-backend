package me.nageshk.omnichannel.db.billing;

import java.util.Collection;
import java.util.List;
import me.nageshk.omnichannel.common.billing.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BillingOrderRepository extends MongoRepository<BillingOrder, String> {

    List<BillingOrder> findByCustomerIdOrderByRaisedAtDescIdDesc(String customerId);

    List<BillingOrder> findByCustomerIdAndStatusOrderByRaisedAtAscIdAsc(String customerId, OrderStatus status);

    Page<BillingOrder> findByStatusIn(Collection<OrderStatus> statuses, Pageable pageable);
}
