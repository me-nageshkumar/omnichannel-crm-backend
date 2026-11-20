package me.nageshk.omnichannel.app.support;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Points every test context at the process's shared MongoDB ({@link SharedMongo}): the shared database, or one of its
 * own for a test marked {@link OwnDatabase}. The database name is part of the context's cache key.
 */
public class SharedMongoContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> attrs) {
        String database = AnnotatedElementUtils.hasAnnotation(testClass, OwnDatabase.class)
                ? "own_" + testClass.getSimpleName()
                : SharedMongo.SHARED_DATABASE;
        return new Customizer(database);
    }

    private static final class Customizer implements ContextCustomizer {

        private final String database;

        Customizer(String database) {
            this.database = database;
        }

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            String uri = "mongodb://" + SharedMongo.address() + "/" + database;
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("sharedMongo", Map.of("spring.mongodb.uri", uri)));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Customizer c && c.database.equals(database);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Customizer.class, database);
        }
    }
}
