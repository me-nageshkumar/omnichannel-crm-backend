package me.nageshk.omnichannel.campaign;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** How campaigns of one workspace pace and protect their senders. */
@Document("campaignSettings")
@Getter
@Setter
public class CampaignSettings {

    @Id
    private String id;
    @Indexed(unique = true)
    private String workspaceId;
    private String timezone;
    private String defaultCountryCode;
    private List<DayOfWeek> workingDays;
    /** HH:mm in {@link #timezone}. */
    private String startTime;
    private String endTime;
    private int gapMinSeconds;
    private int gapMaxSeconds;
    private int skipGapMinSeconds;
    private int skipGapMaxSeconds;
    private int hourlyCap;
    private List<WarmupStep> warmupCaps;
    private int matureCap;
    private int healthyCap;
    private int restDays;
    private int recontactDays;
    private List<String> stopWords;
    @LastModifiedDate
    private Instant lastUpdateTime;
    @Version
    private Long version;

    /** From sender age {@code fromDay} on, at most {@code cap} sends a day. */
    public record WarmupStep(int fromDay, int cap) {
    }
}
