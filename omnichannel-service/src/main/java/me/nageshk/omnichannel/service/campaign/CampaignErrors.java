package me.nageshk.omnichannel.service.campaign;

import java.util.Map;
import me.nageshk.omnichannel.common.api.CodedException;
import org.springframework.http.HttpStatus;

/** The campaign API's coded failures. */
public final class CampaignErrors {

    private CampaignErrors() {
    }

    public static CodedException notEditable() {
        return new CodedException(HttpStatus.CONFLICT, "CAMPAIGN_NOT_EDITABLE", null,
                "Only a draft campaign can be edited.");
    }

    /** {@code verb} is started, paused, resumed or terminated. */
    public static CodedException state(String verb) {
        return new CodedException(HttpStatus.CONFLICT, "CAMPAIGN_STATE", null,
                "This campaign cannot be " + verb + " now.");
    }

    public static CodedException templateHasLink(String field) {
        return new CodedException(HttpStatus.BAD_REQUEST, "TEMPLATE_HAS_LINK", field,
                "Campaign messages cannot contain links.");
    }

    public static CodedException templateNotText() {
        return new CodedException(HttpStatus.BAD_REQUEST, "TEMPLATE_NOT_TEXT", "templateIds",
                "Only text templates can be used in campaigns.");
    }

    public static CodedException senderNotAvailable(String number) {
        return new CodedException(HttpStatus.BAD_REQUEST, "SENDER_NOT_AVAILABLE", "senderIds",
                "+" + number + " cannot send campaigns right now.", Map.of("number", number));
    }

    public static CodedException placeholderUnmapped() {
        return new CodedException(HttpStatus.BAD_REQUEST, "PLACEHOLDER_UNMAPPED", "placeholders",
                "Every placeholder needs a value.");
    }

    public static CodedException columnReused() {
        return new CodedException(HttpStatus.BAD_REQUEST, "MAPPING_COLUMN_REUSED", "placeholders",
                "A CSV column can be used only once.");
    }

    public static CodedException phoneColumnRequired() {
        return new CodedException(HttpStatus.BAD_REQUEST, "PHONE_COLUMN_REQUIRED", "audience.phoneColumn",
                "Choose the column that holds the phone number.");
    }

    /** {@code verb} is rested, retired, reactivated or resumed. */
    public static CodedException senderState(String verb) {
        return new CodedException(HttpStatus.CONFLICT, "SENDER_STATE", null,
                "This number cannot be " + verb + " now.");
    }
}
