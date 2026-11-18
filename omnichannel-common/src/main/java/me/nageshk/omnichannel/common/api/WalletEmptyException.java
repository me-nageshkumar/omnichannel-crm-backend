package me.nageshk.omnichannel.common.api;

import org.springframework.http.HttpStatus;

/** The customer's wallet does not hold what a charge needs; nothing was taken. Answered as 402 WALLET_EMPTY. */
public class WalletEmptyException extends CodedException {

    public static final String CODE = "WALLET_EMPTY";

    private final String customerId;

    public WalletEmptyException(String customerId) {
        super(HttpStatus.PAYMENT_REQUIRED, CODE, null, "The wallet balance is too low to send");
        this.customerId = customerId;
    }

    public String customerId() { return customerId; }
}
