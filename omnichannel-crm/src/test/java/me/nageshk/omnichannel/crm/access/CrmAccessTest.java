package me.nageshk.omnichannel.crm.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.ForbiddenException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.crm.platform.NumberDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformNumber;
import me.nageshk.omnichannel.crm.settings.CrmSettings;
import me.nageshk.omnichannel.crm.settings.CrmSettingsService;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CrmAccessTest {

    private static final String WS = "ws1";

    private final AccessService access = mock(AccessService.class);
    private final CrmSettingsService settings = mock(CrmSettingsService.class);
    private final AuthenticatedUser me = mock(AuthenticatedUser.class);
    private Set<String> assigned = Set.of();
    private Set<String> existing = Set.of();
    private CrmAccess crmAccess;

    @BeforeEach
    void setUp() {
        when(me.userId()).thenReturn("u1");
        when(me.customerId()).thenReturn("c1");
        NumberDirectory numbers = new NumberDirectory() {
            @Override public List<PlatformNumber> numbers(String ws) {
                return List.of(new PlatformNumber("n1", "9100", "Main"), new PlatformNumber("n2", "9200", null));
            }
            @Override public Set<String> chattable(AuthenticatedUser who, String ws) { return Set.of("n1"); }
        };
        PipelineAccessReader reader = (ws, userId) -> assigned;
        PipelineIds ids = new PipelineIds() {
            @Override public Set<String> existing(String ws) { return existing; }
            @Override public Set<String> active(String ws) { return existing; }
        };
        crmAccess = new CrmAccess(access, settings, numbers, reader, ids);
        CrmSettings s = new CrmSettings();
        s.setEnabled(true);
        s.setWorkspaceId(WS);
        when(settings.find(WS)).thenReturn(Optional.of(s));
    }

    private void holds(String... codes) {
        when(access.inWorkspace(any(), any())).thenReturn(Set.of(codes));
    }

    @Test
    void viewAllGetsAllPipelines() {
        holds("crm:view", "crm:view-all");
        CrmContext ctx = crmAccess.resolve(me, WS);
        assertThat(ctx.pipelines().all()).isTrue();
        assertThat(ctx.viewAll()).isTrue();
        assertThat(ctx.userId()).isEqualTo("u1");
        assertThat(ctx.customerId()).isEqualTo("c1");
    }

    @Test
    void restrictedGetsAssignedIntersectExisting() {
        holds("crm:view");
        assigned = Set.of("p1", "gone");
        existing = Set.of("p1", "p2");
        CrmContext ctx = crmAccess.resolve(me, WS);
        assertThat(ctx.pipelines().all()).isFalse();
        assertThat(ctx.pipelines().pipelineIds()).containsExactly("p1");
        assertThat(ctx.pipelines().allows("p1")).isTrue();
        assertThat(ctx.pipelines().allows("p2")).isFalse();
    }

    @Test
    void offBrandThrows402() {
        holds("crm:view");
        CrmSettings off = new CrmSettings();
        when(settings.find(WS)).thenReturn(Optional.of(off));
        assertThatThrownBy(() -> crmAccess.resolve(me, WS)).isInstanceOf(CodedException.class)
                .extracting(e -> ((CodedException) e).code()).isEqualTo("CRM_NOT_ENABLED");
        when(settings.find(WS)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> crmAccess.resolve(me, WS)).isInstanceOf(CodedException.class);
        assertThat(crmAccess.resolveForStatus(me, WS).settings().isEnabled()).isFalse();
    }

    @Test
    void noViewThrows403() {
        holds("crm:edit");
        assertThatThrownBy(() -> crmAccess.resolve(me, WS)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void brandWithoutAccessIs404() {
        when(access.inWorkspace(any(), any())).thenThrow(new NotFoundException("workspace not found"));
        assertThatThrownBy(() -> crmAccess.resolve(me, WS)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void chattableComesFromNumberDirectory() {
        holds("crm:view", "crm:view-all");
        CrmContext ctx = crmAccess.resolve(me, WS);
        assertThat(ctx.numbers()).extracting(PlatformNumber::id).containsExactly("n1", "n2");
        assertThat(ctx.chattable()).containsExactly("n1");
    }

    @Test
    void criteriaOfPipelineAccess() {
        assertThat(PipelineAccess.ALL.criteria("pipelineId").getCriteriaObject()).isEmpty();
        assertThat(new PipelineAccess(false, Set.of()).criteria("pipelineId").getCriteriaObject().toString())
                .contains("$in");
        assertThat(new PipelineAccess(false, Set.of("a")).allows("a")).isTrue();
    }
}
