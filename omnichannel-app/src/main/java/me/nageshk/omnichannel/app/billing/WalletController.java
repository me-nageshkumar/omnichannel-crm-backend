package me.nageshk.omnichannel.app.billing;

import java.util.List;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.billing.wallet.WalletQueryService;
import me.nageshk.omnichannel.service.billing.wallet.WalletService;
import me.nageshk.omnichannel.service.billing.wallet.WalletViews.AdjustRequest;
import me.nageshk.omnichannel.service.billing.wallet.WalletViews.EntryPage;
import me.nageshk.omnichannel.service.billing.wallet.WalletViews.ThresholdRequest;
import me.nageshk.omnichannel.service.billing.wallet.WalletViews.UsageDay;
import me.nageshk.omnichannel.service.billing.wallet.WalletViews.WalletView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform staff: a customer's usage wallet. */
@RestController
@RequestMapping("/api/billing/customers/{customerId}/wallet")
public class WalletController {

    private final WalletQueryService queries;
    private final WalletService wallets;

    public WalletController(WalletQueryService queries, WalletService wallets) {
        this.queries = queries;
        this.wallets = wallets;
    }

    @GetMapping
    @PreAuthorize(OrderController.VIEW)
    public WalletView get(@PathVariable String customerId) {
        return queries.view(customerId);
    }

    @GetMapping("/entries")
    @PreAuthorize(OrderController.VIEW)
    public EntryPage entries(@PathVariable String customerId, @RequestParam(defaultValue = "0") int page) {
        return queries.entries(customerId, page, true);
    }

    @GetMapping("/usage")
    @PreAuthorize(OrderController.VIEW)
    public List<UsageDay> usage(@PathVariable String customerId, @RequestParam(defaultValue = "30") int days) {
        return queries.usage(customerId, days);
    }

    @PostMapping("/adjustments")
    @PreAuthorize(OrderController.MANAGE)
    public WalletView adjust(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String customerId,
            @RequestBody AdjustRequest req) {
        queries.view(customerId);
        wallets.adjust(customerId, req.amount(), req.note(), me.userId());
        return queries.view(customerId);
    }

    @PutMapping("/threshold")
    @PreAuthorize(OrderController.MANAGE)
    public WalletView threshold(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String customerId,
            @RequestBody ThresholdRequest req) {
        queries.view(customerId);
        wallets.setThreshold(customerId, req.lowBalanceAt(), me.userId());
        return queries.view(customerId);
    }
}
