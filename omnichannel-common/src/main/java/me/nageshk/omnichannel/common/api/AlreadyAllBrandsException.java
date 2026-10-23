package me.nageshk.omnichannel.common.api;

/**
 * A user with a COMPANY role already acts in every workspace of its customer, so it is never given a
 * BRAND role as well: 400 {@code ALREADY_ALL_BRANDS}.
 */
public class AlreadyAllBrandsException extends RuntimeException {

    public static final String CODE = "ALREADY_ALL_BRANDS";

    public AlreadyAllBrandsException(String message) {
        super(message);
    }
}
