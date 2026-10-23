package me.nageshk.omnichannel.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.DisabledAtHigherLevelException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;

class ApiExceptionHandlerTest {

    @Test
    void concurrentModificationIsConflict() {
        var response = new ApiExceptionHandler().conflict(new OptimisticLockingFailureException("stale"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "CONFLICT").containsKey("message");
    }

    @Test
    void disabledAtHigherLevelIs403WithLevel() {
        var response = new ApiExceptionHandler().disabledAtHigherLevel(
                new DisabledAtHigherLevelException(me.nageshk.omnichannel.common.enablement.EnableLevel.PLATFORM));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("code", "DISABLED_AT_HIGHER_LEVEL")
                .containsEntry("level", "PLATFORM")
                .containsEntry("message", "Disabled by platform staff. Only platform staff can enable it.");
    }

    @Test
    void codedExceptionCarriesStatusCodeAndField() {
        var response = new ApiExceptionHandler().coded(new CodedException(HttpStatus.BAD_REQUEST,
                "TEMPLATE_HAS_LINK", "templateIds", "Campaign messages cannot contain links."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsOnly(
                java.util.Map.entry("code", "TEMPLATE_HAS_LINK"),
                java.util.Map.entry("message", "Campaign messages cannot contain links."),
                java.util.Map.entry("field", "templateIds"));
    }

    @Test
    void codedExceptionWithoutFieldHasNoFieldKey() {
        var response = new ApiExceptionHandler().coded(
                me.nageshk.omnichannel.service.campaign.CampaignErrors.notEditable());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsOnlyKeys("code", "message")
                .containsEntry("message", "Only a draft campaign can be edited.");
    }

    @Test
    void codedExceptionExtrasJoinTheBody() {
        var response = new ApiExceptionHandler().coded(
                me.nageshk.omnichannel.service.campaign.CampaignErrors.senderNotAvailable("919800000001"));

        assertThat(response.getBody())
                .containsEntry("code", "SENDER_NOT_AVAILABLE")
                .containsEntry("field", "senderIds")
                .containsEntry("number", "919800000001")
                .containsEntry("message", "+919800000001 cannot send campaigns right now.");
    }
}
