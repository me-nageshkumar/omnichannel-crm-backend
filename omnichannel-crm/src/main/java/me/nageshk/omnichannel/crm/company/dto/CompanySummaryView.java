package me.nageshk.omnichannel.crm.company.dto;

import java.math.BigDecimal;
import java.util.List;
import me.nageshk.omnichannel.crm.contact.dto.CrmContactView;
import me.nageshk.omnichannel.crm.deal.dto.DealView;

/** A company with the contacts and open deals the caller may see; {@code contactCount} counts all,
 *  {@code openDealAmount} the deals the caller may see. */
public record CompanySummaryView(CompanyView company, List<CrmContactView> contacts, List<DealView> openDeals,
        int contactCount, BigDecimal openDealAmount) {}
