package me.nageshk.omnichannel.app.inbox.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@SpringBootTest
class HistoryImportConfigIT {

    @Autowired OmnichannelProperties props;
    @Autowired RoleRepository roles;
    @Autowired ApplicationContext context;

    @Test
    void propertiesHaveDefaults() {
        assertThat(props.history().pageSize()).isEqualTo(100);
        assertThat(props.history().maxMessagesPerChat()).isEqualTo(5000);
        assertThat(props.history().maxEmptyPages()).isEqualTo(3);
        assertThat(props.history().maxIdlePages()).isEqualTo(25);
        assertThat(props.history().autoTick()).isFalse();
        assertThat(props.history().pageDelay()).isEqualTo(Duration.ZERO);
    }

    @Test
    void authorityIsSeededToManagers() {
        assertThat(roles.findByName(BuiltInRoles.WORKSPACE_MANAGER).get().getAuthorities())
                .contains("inbox:import-history");
        assertThat(roles.findByName(BuiltInRoles.AGENT).get().getAuthorities())
                .doesNotContain("inbox:import-history");
    }

    @Test
    void schedulerBeanExists() {
        assertThat(context.getBean("historyImportScheduler", ThreadPoolTaskScheduler.class).getThreadNamePrefix())
                .isEqualTo("history-");
    }
}
