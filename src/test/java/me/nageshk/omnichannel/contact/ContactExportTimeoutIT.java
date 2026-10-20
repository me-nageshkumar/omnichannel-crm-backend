package me.nageshk.omnichannel.contact;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcProperties;
import org.springframework.boot.test.context.SpringBootTest;

/** The streamed CSV export must outlive the default 30 s async timeout. */
@SpringBootTest
class ContactExportTimeoutIT {

    @Autowired WebMvcProperties webMvc;

    @Test
    void asyncRequestTimeoutAllowsLongDownloads() {
        Duration timeout = webMvc.getAsync().getRequestTimeout();
        assertThat(timeout).isNotNull();
        assertThat(timeout).isGreaterThanOrEqualTo(Duration.ofMinutes(10));
    }
}
