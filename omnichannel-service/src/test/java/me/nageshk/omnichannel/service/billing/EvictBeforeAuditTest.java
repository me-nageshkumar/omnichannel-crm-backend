package me.nageshk.omnichannel.service.billing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import me.nageshk.omnichannel.db.billing.BillingSettings;
import me.nageshk.omnichannel.db.billing.BillingSettingsRepository;
import me.nageshk.omnichannel.db.billing.WithoutPlan;
import me.nageshk.omnichannel.security.entitlement.BillingSettingsStore;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import me.nageshk.omnichannel.service.billing.SubscriptionViews.SettingsRequest;
import org.junit.jupiter.api.Test;

/** A stored change is in force even when writing its history row fails. */
class EvictBeforeAuditTest {

    @Test
    void settingsAreEvictedEvenWhenTheHistoryRowFails() {
        BillingSettingsRepository repository = mock(BillingSettingsRepository.class);
        BillingSettingsStore store = mock(BillingSettingsStore.class);
        Entitlements entitlements = mock(Entitlements.class);
        BillingAudit audit = mock(BillingAudit.class);
        when(repository.findById(BillingSettings.ID)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        doThrow(new IllegalStateException("history is down")).when(audit).record(any(), any(), any(), any(), any(), any(), any());
        BillingSettingsService service = new BillingSettingsService(repository, store, entitlements, audit);

        assertThatThrownBy(() -> service.update(new SettingsRequest("Asia/Kolkata", 3, WithoutPlan.READ_ONLY, null), "u1"))
                .isInstanceOf(IllegalStateException.class);

        verify(store).evict();
        verify(entitlements).evictAll();
    }
}
