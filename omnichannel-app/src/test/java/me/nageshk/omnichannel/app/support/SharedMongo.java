package me.nageshk.omnichannel.app.support;

import de.flapdoodle.embed.mongo.commands.MongodArguments;
import de.flapdoodle.embed.mongo.commands.ServerAddress;
import de.flapdoodle.embed.mongo.distribution.Versions;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.embed.process.distribution.Version;
import de.flapdoodle.reverse.TransitionWalker;
import de.flapdoodle.reverse.transitions.Start;
import java.util.HashMap;
import java.util.Map;

/**
 * One embedded MongoDB per test process, shared by every Spring context the process starts (each used to start its
 * own mongod and create every index of a fresh database: about 6 of a context's 8 seconds). Contexts share the database
 * {@link #SHARED_DATABASE}, so its indexes are created once; a test marked {@link OwnDatabase} gets its own. Test
 * commands are on, for the failpoint tests ({@link MongoTestCommands}). Stopped when the process exits.
 */
public final class SharedMongo {

    public static final String VERSION = "7.0.12";
    public static final String SHARED_DATABASE = "omnichannel_test";

    private static volatile String address;

    private SharedMongo() {}

    /** {@code host:port} of the process's mongod, started on first use. */
    public static String address() {
        String a = address;
        if (a != null) return a;
        synchronized (SharedMongo.class) {
            if (address == null) address = start();
            return address;
        }
    }

    private static String start() {
        MongodArguments defaults = MongodArguments.defaults();
        Map<String, String> params = new HashMap<>(defaults.params());
        params.put("enableTestCommands", "1");
        MongodArguments args = MongodArguments.builder().from(defaults).params(params).build();
        TransitionWalker.ReachedState<RunningMongodProcess> running = Mongod.instance()
                .withMongodArguments(Start.to(MongodArguments.class).initializedWith(args))
                .start(Versions.withFeatures(Version.of(VERSION)));
        Runtime.getRuntime().addShutdownHook(new Thread(running::close, "shared-mongo-stop"));
        ServerAddress server = running.current().getServerAddress();
        return server.getHost() + ":" + server.getPort();
    }
}
