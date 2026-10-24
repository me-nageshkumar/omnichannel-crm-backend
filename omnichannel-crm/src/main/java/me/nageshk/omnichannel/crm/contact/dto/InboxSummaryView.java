package me.nageshk.omnichannel.crm.contact.dto;

import java.util.List;
import me.nageshk.omnichannel.crm.deal.dto.DealView;
import me.nageshk.omnichannel.crm.task.dto.TaskView;

/** What the inbox panel shows next to an open chat; {@code nextTask} is the earliest open task on the contact or its open deals. */
public record InboxSummaryView(String chatId, boolean canCapture, String captureBlockedReason,
        CrmContactView contact, List<DealView> openDeals, TaskView nextTask) {}
