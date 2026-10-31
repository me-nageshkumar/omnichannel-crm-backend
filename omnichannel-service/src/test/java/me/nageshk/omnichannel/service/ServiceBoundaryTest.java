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
}
