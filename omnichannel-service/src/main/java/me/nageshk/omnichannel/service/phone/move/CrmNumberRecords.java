package me.nageshk.omnichannel.service.phone.move;

/**
 * The CRM's records that name a number (contacts' channel and origin, deals' channel, activities, notes, tasks),
 * re-pointed by a move; implemented in the app beside the CRM, which the service never imports.
 */
public interface CrmNumberRecords {

    /** Every CRM record naming {@code fromPhoneNumberId} names {@code toPhoneNumberId} instead. Safe to run again. */
    void repoint(String fromPhoneNumberId, String toPhoneNumberId);
}
