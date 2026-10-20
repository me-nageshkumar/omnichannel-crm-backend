package me.nageshk.omnichannel.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import me.nageshk.omnichannel.access.AuthorityCheckScanner.CheckKind;
import me.nageshk.omnichannel.access.AuthorityCheckScanner.FoundCheck;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AuthorityCheckScannerIT {

    @Autowired AuthorityCheckScanner scanner;

    @Test
    void scanFindsRealControllerChecks() {
        Map<String, FoundCheck> found = scanner.found();

        assertThat(found).containsKey("customer:view");
        assertThat(found.get("customer:view").kinds()).contains(CheckKind.GLOBAL);
        assertThat(found.get("customer:view").locations())
                .anyMatch(l -> l.startsWith("CustomerController."));
        assertThat(found).containsKey("user:manage");
        assertThat(found.get("user:manage").kinds()).contains(CheckKind.BRAND);
        assertThat(found).containsKey("test:probe");
        assertThat(found.get("test:probe").locations()).contains("ProbeController.probe");
    }

    @Test
    void scanIgnoresMarkerAndUnformattedStrings() {
        Map<String, FoundCheck> found = scanner.found();

        assertThat(found).doesNotContainKey("SUPER_ADMIN");
        assertThat(found.keySet()).allMatch(k -> k.matches("^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$"));
    }
}
