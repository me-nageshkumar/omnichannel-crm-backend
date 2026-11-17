package me.nageshk.omnichannel.common.api;

import org.springframework.http.HttpStatus;

/** The customer's plan does not cover this, or its subscription has ended: nothing was sent or started. */
public class SubscriptionInactiveException extends CodedException {

    public static final String CODE = "SUBSCRIPTION_INACTIVE";

    public SubscriptionInactiveException(String message) {
        super(HttpStatus.PAYMENT_REQUIRED, CODE, null, message);
    }
}
