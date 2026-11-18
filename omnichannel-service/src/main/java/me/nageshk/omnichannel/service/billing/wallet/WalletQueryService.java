package me.nageshk.omnichannel.service.billing.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.billing.OrderKind;
import me.nageshk.omnichannel.common.billing.UsageMeter;
import me.nageshk.omnichannel.common.billing.UsageSource;
import me.nageshk.omnichannel.common.billing.WalletEntryType;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.InvoiceRepository;
import me.nageshk.omnichannel.db.billing.Wallet;
import me.nageshk.omnichannel.db.billing.WalletEntry;
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.security.entitlement.BillingSettingsStore;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.service.billing.wallet.WalletViews.EntryPage;
import me.nageshk.omnichannel.service.billing.wallet.WalletViews.UsageDay;
import me.nageshk.omnichannel.service.billing.wallet.WalletViews.WalletEntryView;
import me.nageshk.omnichannel.service.billing.wallet.WalletViews.WalletView;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Reads of a wallet: its balance, its ledger and what was used by day. */
@Service
public class WalletQueryService {

    static final int PAGE_SIZE = 50;

    private final WalletService wallets;
    private final WalletMover mover;
    private final CustomerRepository customers;
    private final WorkspaceRepository workspaces;
    private final UserRepository users;
    private final InvoiceRepository invoices;
    private final Entitlements entitlements;
    private final BillingSettingsStore settings;
    private final MongoTemplate mongo;
    private final Clock clock;

    public WalletQueryService(WalletService wallets, WalletMover mover, CustomerRepository customers,
            WorkspaceRepository workspaces, UserRepository users, InvoiceRepository invoices, Entitlements entitlements,
            BillingSettingsStore settings, MongoTemplate mongo, Clock clock) {
        this.wallets = wallets;
        this.mover = mover;
        this.customers = customers;
        this.workspaces = workspaces;
        this.users = users;
        this.invoices = invoices;
        this.entitlements = entitlements;
        this.settings = settings;
        this.mongo = mongo;
        this.clock = clock;
    }

    /** A customer that never topped up reads as an empty wallet. */
    public WalletView view(String customerId) {
        requireCustomer(customerId);
        Wallet w = wallets.find(customerId).orElse(null);
        BigDecimal balance = w == null ? WalletService.scaled(BigDecimal.ZERO) : WalletService.scaled(w.getBalance());
        BigDecimal at = w == null ? null : w.getLowBalanceAt();
        return new WalletView(balance, at, at != null && balance.compareTo(at) < 0,
                entitlements.of(customerId).rate(UsageMeter.WHATSAPP_MESSAGE), refundable(customerId, balance));
    }

    /**
     * What a wallet refund could return, before tax: no more than the wallet holds, and no more than the top-up
     * invoices still have uncredited (money from an adjustment was never paid for, so it is not refundable).
     */
    public BigDecimal refundable(String customerId, BigDecimal balance) {
        BigDecimal left = BigDecimal.ZERO;
        for (Invoice i : topUpInvoices(customerId)) {
            left = left.add(i.getTaxable().subtract(i.getCredited() == null ? BigDecimal.ZERO : i.getCredited()).max(BigDecimal.ZERO));
        }
        return left.min(balance).setScale(2, RoundingMode.DOWN);
    }

    /** The customer's top-up invoices, newest first. */
    public List<Invoice> topUpInvoices(String customerId) {
        return invoices.findByCustomerIdOrderByIssuedAtDescIdDesc(customerId).stream()
                .filter(i -> i.getOrderKind() == OrderKind.TOPUP).toList();
    }

    /**
     * The ledger, newest first: the kept entries and any still inside the wallet.
     *
     * @param staff whether platform staff ask; a customer is not told who did what, nor why staff adjusted
     */
    public EntryPage entries(String customerId, int page, boolean staff) {
        requireCustomer(customerId);
        mover.moveQuietly(customerId);
        int at = Math.max(page, 0);
        Query mine = new Query(Criteria.where("customerId").is(customerId));
        long total = mongo.count(mine, WalletEntry.class);
        List<WalletEntry> kept = mongo.find(mine.with(Sort.by(Sort.Order.desc("at"), Sort.Order.desc("_id")))
                .skip((long) at * PAGE_SIZE).limit(PAGE_SIZE), WalletEntry.class);
        // what the mover could not copy yet is still part of the ledger
        List<WalletEntry> waiting = new ArrayList<>(wallets.find(customerId).map(Wallet::getPending).orElse(List.of()));
        Set<String> keptIds = new HashSet<>();
        kept.forEach(e -> keptIds.add(e.getId()));
        waiting.removeIf(e -> keptIds.contains(e.getId()) || mongo.exists(new Query(Criteria.where("_id").is(e.getId())), WalletEntry.class));
        List<WalletEntry> shown = new ArrayList<>();
        if (at == 0) {
            waiting.sort((a, b) -> b.getAt().compareTo(a.getAt()));
            shown.addAll(waiting);
        }
        shown.addAll(kept);
        Map<String, String> names = new HashMap<>();
        return new EntryPage(shown.stream().map(e -> view(e, staff, names)).toList(), total + waiting.size(), at);
    }

    /**
     * Charged messages by day (in the billing zone) and source, newest day first.
     *
     * @param days how many days back, today included: 1 to 90
     */
    public List<UsageDay> usage(String customerId, int days) {
        requireCustomer(customerId);
        if (days < 1 || days > 90) throw new ValidationException("days", "must be between 1 and 90");
        mover.moveQuietly(customerId);
        ZoneId zone = settings.zone();
        Date from = Date.from(clock.instant().atZone(zone).toLocalDate().minusDays(days - 1L).atStartOfDay(zone).toInstant());
        List<Document> pipeline = List.of(
                new Document("$match", new Document("customerId", customerId)
                        .append("type", new Document("$in", List.of(WalletEntryType.USAGE.name(), WalletEntryType.REVERSAL.name())))
                        .append("at", new Document("$gte", from))),
                new Document("$group", new Document("_id", new Document("day",
                        new Document("$dateToString", new Document("format", "%Y-%m-%d").append("date", "$at").append("timezone", zone.getId())))
                        .append("source", "$source"))
                        .append("messages", new Document("$sum", new Document("$cond",
                                List.of(new Document("$eq", List.of("$type", WalletEntryType.USAGE.name())), 1, -1))))
                        .append("amount", new Document("$sum", "$amount"))),
                new Document("$sort", new Document("_id.day", -1).append("_id.source", 1)));
        List<UsageDay> out = new ArrayList<>();
        for (Document d : mongo.getCollection(mongo.getCollectionName(WalletEntry.class)).aggregate(pipeline)) {
            Document key = d.get("_id", Document.class);
            String source = key.getString("source");
            // entries are signed (a charge is negative): what the day cost is the sum turned round
            BigDecimal cost = d.get("amount", Decimal128.class).bigDecimalValue().negate();
            out.add(new UsageDay(key.getString("day"), source == null ? null : UsageSource.valueOf(source),
                    ((Number) d.get("messages")).longValue(), WalletService.scaled(cost)));
        }
        return out;
    }

    private void requireCustomer(String customerId) {
        if (customerId == null || !customers.existsById(customerId)) throw new NotFoundException("customer not found");
    }

    private WalletEntryView view(WalletEntry e, boolean staff, Map<String, String> names) {
        String workspaceName = e.getWorkspaceId() == null ? null
                : names.computeIfAbsent("w:" + e.getWorkspaceId(),
                        k -> workspaces.findById(e.getWorkspaceId()).map(Workspace::getName).orElse(null));
        String byName = !staff || e.getBy() == null ? null
                : names.computeIfAbsent("u:" + e.getBy(), k -> users.findById(e.getBy()).map(User::getName).orElse("Unknown user"));
        String note = !staff && e.getType() == WalletEntryType.ADJUSTMENT ? null : e.getNote();
        return new WalletEntryView(e.getId(), e.getType(), e.getAmount(), e.getBalanceAfter(), e.getMeter(), e.getSource(),
                e.getWorkspaceId(), workspaceName, e.getRef(), note, byName, e.getAt());
    }
}
