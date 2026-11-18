package me.nageshk.omnichannel.common.api;

/** The customer's wallet does not hold what the charge needs; nothing was taken. */
public class WalletEmptyException extends RuntimeException {

    private final String customerId;

    public WalletEmptyException(String customerId) {
        super("The wallet balance is too low");
        this.customerId = customerId;
    }

    public String customerId() { return customerId; }
}
