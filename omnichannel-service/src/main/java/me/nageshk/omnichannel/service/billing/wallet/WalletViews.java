package me.nageshk.omnichannel.service.billing.wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import me.nageshk.omnichannel.common.billing.UsageSource;
import me.nageshk.omnichannel.common.billing.WalletEntryType;

/** What the billing API takes and returns for a wallet. */
public final class WalletViews {

    private WalletViews() {}

    /**
     * @param low the balance is below {@code lowBalanceAt}
     * @param messageRate rupees per message on the term in force; null when messages are not charged
     * @param refundable what a wallet refund could return, before tax: top-up money still in the wallet
     */
    public record WalletView(BigDecimal balance, BigDecimal lowBalanceAt, boolean low, BigDecimal messageRate,
            BigDecimal refundable) {}

    /** {@code amount} is signed. A customer is not sent {@code byName}, nor the note of an adjustment. */
    public record WalletEntryView(String id, WalletEntryType type, BigDecimal amount, BigDecimal balanceAfter,
            UsageMeter meter, UsageSource source, String workspaceId, String workspaceName, String ref, String note,
            String byName, Instant at) {}

    public record EntryPage(List<WalletEntryView> items, long total, int page) {}

    /** One day's charged messages from one source: {@code amount} is what they cost, reversals taken off. */
    public record UsageDay(String day, UsageSource source, long messages, BigDecimal amount) {}

    public record AdjustRequest(BigDecimal amount, String note) {}

    public record ThresholdRequest(BigDecimal lowBalanceAt) {}
}
