package me.nageshk.omnichannel.service.phone.move;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Moves of numbers, newest last. */
public interface PhoneNumberMoveRepository extends MongoRepository<PhoneNumberMove, String> {

    Optional<PhoneNumberMove> findByUnfinishedOf(String phoneNumberId);

    Optional<PhoneNumberMove> findFirstByPhoneNumberIdOrderByIdDesc(String phoneNumberId);
}
