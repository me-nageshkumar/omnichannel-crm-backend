package me.nageshk.omnichannel.crm.company.dto;

import java.math.BigDecimal;
import java.util.List;
import me.nageshk.omnichannel.crm.contact.dto.CrmContactView;

/** A company with the contacts the caller may see; {@code contactCount} counts all, {@code openDealAmount} the
 *  deals the caller may see. */
public record CompanySummaryView(CompanyView company, List<CrmContactView> contacts, int contactCount,
        BigDecimal openDealAmount) {}
