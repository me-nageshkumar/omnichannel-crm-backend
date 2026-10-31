package me.nageshk.omnichannel.app.support;

import com.mongodb.client.MongoClient;
import de.flapdoodle.embed.mongo.commands.ImmutableMongodArguments;
import de.flapdoodle.embed.mongo.commands.MongodArguments;
import java.util.HashMap;
import java.util.List;
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

    private static final int INTERNAL_ERROR = 1;

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

    /** Fails the next {@code times} of the commands on the collection with an internal error. */
    public static void failNext(MongoClient client, String database, String collection, String command, int times) {
        failCommand(client, new Document("times", times), database, collection, command,
                new Document("errorCode", INTERNAL_ERROR));
    }

    /** Lets the next {@code pass} of the commands on the collection through, then fails all others until {@link #allowCommands}. */
    public static void failAfter(MongoClient client, String database, String collection, String command, int pass) {
        failCommand(client, new Document("skip", pass), database, collection, command,
                new Document("errorCode", INTERNAL_ERROR));
    }

    /** Holds each of the next {@code times} commands on the collection for {@code millis}, then runs it normally. */
    public static void delayNext(MongoClient client, String database, String collection, String command, int times,
            int millis) {
        failCommand(client, new Document("times", times), database, collection, command,
                new Document("blockConnection", true).append("blockTimeMS", millis));
    }

    /** Switches off whatever the methods above switched on. */
    public static void allowCommands(MongoClient client) {
        client.getDatabase("admin").runCommand(new Document("configureFailPoint", "failCommand").append("mode", "off"));
    }

    private static void failCommand(MongoClient client, Document mode, String database, String collection,
            String command, Document effect) {
        Document data = new Document("failCommands", List.of(command)).append("namespace", database + "." + collection);
        data.putAll(effect);
        client.getDatabase("admin").runCommand(new Document("configureFailPoint", "failCommand").append("mode", mode)
                .append("data", data));
    }
}
