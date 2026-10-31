package me.nageshk.omnichannel.crm.segment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.access.PipelineAccess;
import me.nageshk.omnichannel.crm.platform.CampaignDirectory;
import me.nageshk.omnichannel.crm.platform.ChannelContactQuery;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

class SegmentServiceTest {

    @Test
    void theNameKeyDoesNotDependOnTheDefaultLocale() {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));
        try {
            assertThat(SegmentService.nameKey("TITLE")).isEqualTo("title");
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void deletingASegmentChangedSinceItWasReadIsAConflict() {
        CrmSegmentRepository repository = mock(CrmSegmentRepository.class);
        CampaignDirectory campaigns = mock(CampaignDirectory.class);
        CrmSegment segment = new CrmSegment();
        when(repository.findByIdAndWorkspaceId("s1", "w1")).thenReturn(Optional.of(segment));
        when(campaigns.usingSegment("w1", "s1")).thenReturn(List.of());
        doThrow(new OptimisticLockingFailureException("stale")).when(repository).delete(segment);
        SegmentService service = new SegmentService(repository, mock(MongoTemplate.class), mock(SegmentResolver.class),
                mock(SegmentCatalog.class), campaigns, mock(ChannelContactQuery.class), mock(CrmAccess.class),
                Clock.systemUTC());
        CrmContext ctx = new CrmContext("w1", "c1", "u1", Set.of("crm:segments"), null, List.of(), Set.of(),
                PipelineAccess.ALL);
        assertThatThrownBy(() -> service.delete(ctx, "s1")).isInstanceOfSatisfying(CodedException.class, e -> {
            assertThat(e.status().value()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
        });
    }
}
