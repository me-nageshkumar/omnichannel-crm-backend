package me.nageshk.omnichannel.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MetaModuleBoundaryTest {

    private static final String ROOT = "me.nageshk.omnichannel.";
    private static final List<String> ALLOWED = List.of(
            ROOT + "meta.", ROOT + "common.", ROOT + "security.access.", ROOT + "security.auth.");

    @Test
    void metaImportsOnlyCommonAndSecurity() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String t = line.trim();
                    if (!t.startsWith("import ")) continue;
                    String target = t.substring("import ".length()).replaceFirst("^static\\s+", "");
                    if (target.startsWith(ROOT) && ALLOWED.stream().noneMatch(target::startsWith)) {
                        violations.add(file + ": " + t);
                    }
                }
            }
        }
        assertThat(violations).as("forbidden imports (static imports included)").isEmpty();
    }
}
