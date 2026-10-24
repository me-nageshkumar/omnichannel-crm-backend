package me.nageshk.omnichannel.crm.common;

import java.util.Map;
import me.nageshk.omnichannel.common.api.CodedException;
import org.springframework.http.HttpStatus;

/** Factories of the CRM's coded failures. */
public final class CrmErrors {

    public static final String NOT_ENABLED = "CRM_NOT_ENABLED";

    private CrmErrors() {}

    public static CodedException notEnabled() {
        return new CodedException(HttpStatus.PAYMENT_REQUIRED, NOT_ENABLED, null, "CRM is not enabled for this brand");
    }

    public static CodedException conflict(String code, String message, Map<String, String> extra) {
        return new CodedException(HttpStatus.CONFLICT, code, null, message, extra);
    }

    public static CodedException forbidden(String code, String message) {
        return new CodedException(HttpStatus.FORBIDDEN, code, null, message);
    }

    public static CodedException badRequest(String code, String message) {
        return new CodedException(HttpStatus.BAD_REQUEST, code, null, message);
    }

    public static CodedException badRequest(String code, String message, Map<String, String> extra) {
        return new CodedException(HttpStatus.BAD_REQUEST, code, null, message, extra);
    }
}
