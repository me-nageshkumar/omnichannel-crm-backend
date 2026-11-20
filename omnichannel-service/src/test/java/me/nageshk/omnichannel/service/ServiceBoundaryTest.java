package me.nageshk.omnichannel.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The host never imports the CRM module: campaigns reach segments through their own ports (spec D D1, C12). */
class ServiceBoundaryTest {

    @Test
    void serviceNeverImportsTheCrmModule() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String t = line.trim();
                    if (t.startsWith("import ") && t.replaceFirst("^import\\s+static\\s+", "import ")
                            .startsWith("import me.nageshk.omnichannel.crm.")) {
                        violations.add(file + ": " + t);
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void serviceImportsOnlyTheAiApi() throws IOException {
        String ai = "me.nageshk.omnichannel.ai.";
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String t = line.trim();
                    if (!t.startsWith("import ")) continue;
                    String target = t.substring("import ".length()).replaceFirst("^static\\s+", "");
                    if (target.startsWith(ai) && !target.startsWith(ai + "api.")) violations.add(file + ": " + t);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void serviceImportsOnlyTheMetaApi() throws IOException {
        String meta = "me.nageshk.omnichannel.meta.";
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String t = line.trim();
                    if (!t.startsWith("import ")) continue;
                    String target = t.substring("import ".length()).replaceFirst("^static\\s+", "");
                    if (target.startsWith(meta) && !target.startsWith(meta + "api.")) violations.add(file + ": " + t);
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
