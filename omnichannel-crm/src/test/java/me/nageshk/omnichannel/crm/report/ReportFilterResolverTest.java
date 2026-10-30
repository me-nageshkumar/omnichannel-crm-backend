package me.nageshk.omnichannel.crm.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.access.PipelineAccess;
import me.nageshk.omnichannel.crm.access.PipelineIds;
import me.nageshk.omnichannel.crm.platform.PlatformNumber;
import me.nageshk.omnichannel.crm.settings.CrmSettings;
import org.junit.jupiter.api.Test;

class ReportFilterResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final PipelineIds pipelineIds = new PipelineIds() {
        @Override
        public Set<String> existing(String workspaceId) {
            return Set.of("sales", "support");
        }

        @Override
        public Set<String> active(String workspaceId) {
            return Set.of("sales", "support");
        }
    };
    private final ReportFilterResolver resolver = new ReportFilterResolver(pipelineIds, clock);

    private static CrmContext ctx(PipelineAccess access) {
        return new CrmContext("ws", "cust", "u1", Set.of("crm:view"), new CrmSettings(),
                List.of(new PlatformNumber("n1", "919900000001", "Sales")), Set.of("n1"), access);
    }

    private static ReportFilter filter(String from, String to, String tz) {
        return new ReportFilter(from, to, tz, null, null, null);
    }

    private static String field(Throwable t) {
        return ((CodedException) t).extra().get("field");
    }

    @Test
    void defaultsToLastNinetyDaysUtc() {
        ResolvedFilter f = resolver.resolve(ctx(PipelineAccess.ALL), new ReportFilter(null, null, null, null, null, null));
        assertThat(f.to()).isEqualTo(NOW);
        assertThat(f.from()).isEqualTo(NOW.minus(java.time.Duration.ofDays(90)));
        assertThat(f.zone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(f.pipelineId()).isNull();
        assertThat(f.phoneNumberIds()).isEmpty();
        assertThat(f.ownerIds()).isEmpty();
    }

    @Test
    void refusesFromAfterTo() {
        assertThatThrownBy(() -> resolver.resolve(ctx(PipelineAccess.ALL),
                filter("2026-10-30T00:00:00Z", "2026-10-29T00:00:00Z", null)))
                .isInstanceOfSatisfying(CodedException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_REPORT_FILTER");
                    assertThat(field(e)).isEqualTo("from");
                });
    }

    @Test
    void refusesRangeOver731Days() {
        assertThatThrownBy(() -> resolver.resolve(ctx(PipelineAccess.ALL),
                filter("2024-10-01T00:00:00Z", "2026-10-30T00:00:00Z", null)))
                .isInstanceOfSatisfying(CodedException.class, e -> assertThat(field(e)).isEqualTo("to"));
        ResolvedFilter ok = resolver.resolve(ctx(PipelineAccess.ALL),
                filter("2024-10-30T00:00:00Z", "2026-10-30T00:00:00Z", null));
        assertThat(ok.from()).isEqualTo(Instant.parse("2024-10-30T00:00:00Z"));
    }

    @Test
    void refusesNonRegionZone() {
        for (String tz : List.of("+05:30", "GMT+5", "Mars/Base", "Z")) {
            assertThatThrownBy(() -> resolver.resolve(ctx(PipelineAccess.ALL), filter(null, null, tz)))
                    .as(tz).isInstanceOfSatisfying(CodedException.class, e -> assertThat(field(e)).isEqualTo("tz"));
        }
    }

    @Test
    void acceptsRegionZoneAndUtc() {
        assertThat(resolver.resolve(ctx(PipelineAccess.ALL), filter(null, null, "Asia/Kolkata")).zone())
                .isEqualTo(ZoneId.of("Asia/Kolkata"));
        assertThat(resolver.resolve(ctx(PipelineAccess.ALL), filter(null, null, " UTC ")).zone())
                .isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void refusesInaccessiblePipeline() {
        CrmContext restricted = ctx(new PipelineAccess(false, Set.of("support")));
        assertThatThrownBy(() -> resolver.resolve(restricted, new ReportFilter(null, null, null, "sales", null, null)))
                .isInstanceOfSatisfying(CodedException.class, e -> assertThat(field(e)).isEqualTo("pipelineId"));
        assertThatThrownBy(() -> resolver.resolve(ctx(PipelineAccess.ALL),
                new ReportFilter(null, null, null, "gone", null, null)))
                .isInstanceOfSatisfying(CodedException.class, e -> assertThat(field(e)).isEqualTo("pipelineId"));
        assertThat(resolver.resolve(restricted, new ReportFilter(null, null, null, "support", null, null))
                .pipelineId()).isEqualTo("support");
    }

    @Test
    void refusesUnknownNumber() {
        assertThatThrownBy(() -> resolver.resolve(ctx(PipelineAccess.ALL),
                new ReportFilter(null, null, null, null, List.of("n1", "n9"), null)))
                .isInstanceOfSatisfying(CodedException.class, e -> assertThat(field(e)).isEqualTo("phoneNumberIds"));
        ResolvedFilter f = resolver.resolve(ctx(PipelineAccess.ALL),
                new ReportFilter(null, null, null, null, List.of("n1"), List.of("u2", " ", "u2")));
        assertThat(f.phoneNumberIds()).containsExactly("n1");
        assertThat(f.ownerIds()).containsExactly("u2");
    }
}
