package me.nageshk.omnichannel.db.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.ClassUtils;

/** Scheduling serves the service layer; the persistence module must not switch it on or define its scheduler. */
class NoSchedulingInDbTest {

    @Test
    void noConfigurationInTheDbModuleEnablesSchedulingOrDefinesATaskScheduler() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Configuration.class));
        List<String> offenders = new ArrayList<>();
        for (BeanDefinition d : scanner.findCandidateComponents("me.nageshk.omnichannel.db")) {
            Class<?> type = ClassUtils.forName(d.getBeanClassName(), getClass().getClassLoader());
            if (type.isAnnotationPresent(EnableScheduling.class)) offenders.add(type.getSimpleName() + " @EnableScheduling");
            for (var m : type.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Bean.class) && TaskScheduler.class.isAssignableFrom(m.getReturnType())) {
                    offenders.add(type.getSimpleName() + "." + m.getName() + "()");
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
