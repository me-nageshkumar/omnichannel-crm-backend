package me.nageshk.omnichannel.service.billing.order;

/**
 * Who asks.
 *
 * @param customerId the customer a customer's user belongs to; null for platform staff
 */
public record Caller(String userId, boolean staff, String customerId) {

    public static Caller staff(String userId) {
        return new Caller(userId, true, null);
    }

    public static Caller customer(String userId, String customerId) {
        return new Caller(userId, false, customerId);
    }
}
