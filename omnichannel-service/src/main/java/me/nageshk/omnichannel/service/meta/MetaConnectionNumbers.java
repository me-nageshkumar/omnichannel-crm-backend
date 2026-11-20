package me.nageshk.omnichannel.service.meta;

import java.util.List;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.meta.api.CloudAccountInfo;
import me.nageshk.omnichannel.meta.api.CloudAccounts;
import me.nageshk.omnichannel.meta.api.ConnectionNumber;
import me.nageshk.omnichannel.meta.api.ConnectionNumbers;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** A Meta connection's numbers with their labels, which live on the host's phone numbers. */
@Component
class MetaConnectionNumbers implements ConnectionNumbers {

    private final CloudAccounts accounts;
    private final PhoneNumberRepository numbers;

    MetaConnectionNumbers(@Lazy CloudAccounts accounts, PhoneNumberRepository numbers) {
        this.accounts = accounts;
        this.numbers = numbers;
    }

    @Override
    public List<ConnectionNumber> numbers(String connectionId) {
        List<String> ids = accounts.byConnection(connectionId).stream().map(CloudAccountInfo::phoneNumberId).toList();
        return numbers.findAllById(ids).stream()
                .map(n -> new ConnectionNumber(n.getId(), n.getNumber(), n.getLabel())).toList();
    }
}
