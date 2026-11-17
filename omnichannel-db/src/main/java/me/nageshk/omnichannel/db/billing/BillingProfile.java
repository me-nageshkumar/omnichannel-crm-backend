package me.nageshk.omnichannel.db.billing;

/** Who a customer is on an invoice. The GSTIN is optional. */
public record BillingProfile(String legalName, String address, String stateCode, String gstin) {

    /** Enough to issue an invoice: a name, an address and a state. */
    public boolean complete() {
        return filled(legalName) && filled(address) && filled(stateCode);
    }

    private static boolean filled(String s) {
        return s != null && !s.isBlank();
    }
}
