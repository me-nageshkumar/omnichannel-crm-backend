package me.nageshk.omnichannel.crm.task;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** One step of a task's checklist (spec E C7); embedded in {@link CrmTask}, order = list order. */
@Getter
@Setter
public class ChecklistItem {

    /** A 24-hex ObjectId string assigned by the server. */
    private String id;
    private String text;
    private boolean done;
    private Instant doneAt;
    private String doneBy;
}
