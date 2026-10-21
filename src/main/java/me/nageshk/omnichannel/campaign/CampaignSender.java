package me.nageshk.omnichannel.campaign;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A phone number's campaign state: its warm-up, rests, pauses and pacing. One per number. */
@Document("campaign_senders")
@Getter
@Setter
public class CampaignSender {

    @Id
    private String id;
    @Indexed(unique = true)
    private String phoneNumberId;
    @Indexed
    private String workspaceId;
    private SenderState state;
    private Instant warmupStartedAt;
    /** Days with at least one send attempt since the warm-up began. */
    private int sendingDays;
    private LocalDate lastSendingDay;
    private Instant restUntil;
    /** Free text: where the browser of this number runs. */
    private String runsOn;
    private Instant nextSendAt;
    private Instant pausedUntil;
    private String pauseReason;
    private Instant halfPaceUntil;
    private boolean lowReplyRate;
    /** Minutes after the window opens that today's sending starts. */
    private int dayStartOffsetMinutes;
    private LocalDate dayStartFor;
    private Instant logoutAt;
    private Instant lastSendAt;
    @CreatedDate
    private Instant creationTime;
    @Version
    private Long version;
}
