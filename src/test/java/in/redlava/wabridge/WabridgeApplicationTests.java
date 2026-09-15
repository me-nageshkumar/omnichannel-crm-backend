package in.redlava.wabridge;

import static org.assertj.core.api.Assertions.assertThat;

import in.redlava.wabridge.config.WabridgeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class WabridgeApplicationTests {

    @Autowired WabridgeProperties props;

    @Test
    void contextLoadsAndBindsProperties() {
        assertThat(props.users()).extracting(WabridgeProperties.User::username).contains("demo");
        assertThat(props.rpc().timeout().toSeconds()).isEqualTo(30);
        assertThat(props.jwt().secret().getBytes().length).isGreaterThanOrEqualTo(32);
    }
}
