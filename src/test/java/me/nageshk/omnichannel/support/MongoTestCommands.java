package me.nageshk.omnichannel.support;

import com.mongodb.client.MongoClient;
import de.flapdoodle.embed.mongo.commands.ImmutableMongodArguments;
import de.flapdoodle.embed.mongo.commands.MongodArguments;
import java.util.HashMap;
import java.util.Map;
import org.bson.Document;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Starts the embedded mongod with test commands enabled, so a test can switch on failpoints. Import it only into
 * the test that needs it: it replaces flapdoodle's default {@link MongodArguments} (a
 * {@code @ConditionalOnMissingBean}), keeping every default and adding {@code enableTestCommands=1}.
 */
@TestConfiguration
public class MongoTestCommands {

    @Bean
    MongodArguments mongodArguments() {
        ImmutableMongodArguments defaults = MongodArguments.defaults();
        Map<String, String> params = new HashMap<>(defaults.params());
        params.put("enableTestCommands", "1");
        return defaults.withParams(params);
    }

    /** While on, every operation with a time limit fails at once with a time-limit error. */
    public static void alwaysTimeOut(MongoClient client, boolean on) {
        client.getDatabase("admin").runCommand(
                new Document("configureFailPoint", "maxTimeAlwaysTimeOut").append("mode", on ? "alwaysOn" : "off"));
    }
}
