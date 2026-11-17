package me.nageshk.omnichannel.db.billing;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface FeatureGroupRepository extends MongoRepository<FeatureGroup, String> {

    List<FeatureGroup> findAllByOrderBySortOrderAscCodeAsc();
}
