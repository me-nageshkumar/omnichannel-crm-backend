package me.nageshk.omnichannel.service.phone.move;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Moves of numbers, newest last. */
public interface PhoneNumberMoveRepository extends MongoRepository<PhoneNumberMove, String> {

    Optional<PhoneNumberMove> findByUnfinishedOf(String phoneNumberId);

    /** Every move that has not finished: few, and each number has at most one. */
    List<PhoneNumberMove> findByUnfinishedOfNotNull();

    Optional<PhoneNumberMove> findFirstByPhoneNumberIdOrderByIdDesc(String phoneNumberId);
}
