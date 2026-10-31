package me.nageshk.omnichannel.crm.job;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The lease on one CRM job, shared by every instance: the job runs over all brands, so there is no workspaceId here.
 * One document per job, never deleted; the lease is free once {@code lockedUntil} has passed.
 */
@Document("crmJobLocks")
@Getter
@Setter
public class CrmJobLock {

    /** The job's name. */
    @Id
    private String job;
    /** One run's random token, so a run that outlived its lease cannot touch the next holder's. */
    private String holder;
    private Instant lockedUntil;
}
