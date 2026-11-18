package me.nageshk.omnichannel.security.entitlement;

import me.nageshk.omnichannel.db.billing.Wallet;
import me.nageshk.omnichannel.db.billing.WalletRepository;
import org.springframework.stereotype.Component;

/** Reads a customer's wallet for the session; it never changes one. */
@Component
public class WalletReader {

    private final WalletRepository wallets;

    public WalletReader(WalletRepository wallets) {
        this.wallets = wallets;
    }

    /** True when the balance is below the customer's warning level; false without a wallet or a level. */
    public boolean low(String customerId) {
        Wallet w = wallets.findByCustomerId(customerId).orElse(null);
        return w != null && w.getLowBalanceAt() != null && w.getBalance() != null
                && w.getBalance().compareTo(w.getLowBalanceAt()) < 0;
    }
}
