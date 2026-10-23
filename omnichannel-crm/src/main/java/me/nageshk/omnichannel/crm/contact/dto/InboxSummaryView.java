package me.nageshk.omnichannel.crm.contact.dto;

import java.util.List;
import me.nageshk.omnichannel.crm.deal.dto.DealView;

/** What the inbox panel shows next to an open chat. {@code nextTask} is always null in phase A. */
public record InboxSummaryView(String chatId, boolean canCapture, String captureBlockedReason,
        CrmContactView contact, List<DealView> openDeals, Object nextTask) {}
