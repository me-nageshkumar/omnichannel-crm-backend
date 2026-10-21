package me.nageshk.omnichannel.campaign;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.api.ValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class CampaignSettingsService {

    private static final Pattern COUNTRY_CODE = Pattern.compile("^[1-9][0-9]{0,2}$");
    private static final Pattern TIME = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");

    private final CampaignSettingsRepository repository;

    CampaignSettingsService(CampaignSettingsRepository repository) {
        this.repository = repository;
    }

    /** The workspace's settings; the first read stores the defaults. */
    CampaignSettings get(String workspaceId) {
        return repository.findByWorkspaceId(workspaceId).orElseGet(() -> {
            try {
                return repository.insert(defaults(workspaceId));
            } catch (DuplicateKeyException raced) {
                return repository.findByWorkspaceId(workspaceId).orElseThrow(() -> raced);
            }
        });
    }

    /** Replaces the settings; {@code body.version()} must be the stored version. */
    CampaignSettings update(String workspaceId, CampaignSettingsView body) {
        List<String> stopWords = new ArrayList<>(normalised(body.stopWords()));
        validate(body, stopWords);
        CampaignSettings s = get(workspaceId);
        if (body.version() == null || !body.version().equals(s.getVersion()))
            throw new OptimisticLockingFailureException("campaign settings changed since they were read");
        s.setTimezone(body.timezone());
        s.setDefaultCountryCode(body.defaultCountryCode());
        s.setWorkingDays(List.copyOf(body.workingDays()));
        s.setStartTime(body.startTime());
        s.setEndTime(body.endTime());
        s.setGapMinSeconds(body.gapMinSeconds());
        s.setGapMaxSeconds(body.gapMaxSeconds());
        s.setSkipGapMinSeconds(body.skipGapMinSeconds());
        s.setSkipGapMaxSeconds(body.skipGapMaxSeconds());
        s.setHourlyCap(body.hourlyCap());
        s.setWarmupCaps(List.copyOf(body.warmupCaps()));
        s.setMatureCap(body.matureCap());
        s.setHealthyCap(body.healthyCap());
        s.setRestDays(body.restDays());
        s.setRecontactDays(body.recontactDays());
        s.setStopWords(stopWords);
        return repository.save(s);
    }

    static CampaignSettings defaults(String workspaceId) {
        CampaignSettings s = new CampaignSettings();
        s.setWorkspaceId(workspaceId);
        s.setTimezone("Asia/Kolkata");
        s.setDefaultCountryCode("91");
        s.setWorkingDays(List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY));
        s.setStartTime("10:00");
        s.setEndTime("18:00");
        s.setGapMinSeconds(45);
        s.setGapMaxSeconds(180);
        s.setSkipGapMinSeconds(15);
        s.setSkipGapMaxSeconds(45);
        s.setHourlyCap(12);
        s.setWarmupCaps(List.of(new CampaignSettings.WarmupStep(1, 5), new CampaignSettings.WarmupStep(4, 10),
                new CampaignSettings.WarmupStep(8, 20), new CampaignSettings.WarmupStep(15, 35),
                new CampaignSettings.WarmupStep(22, 50)));
        s.setMatureCap(75);
        s.setHealthyCap(100);
        s.setRestDays(7);
        s.setRecontactDays(7);
        s.setStopWords(List.of("stop", "unsubscribe", "remove me", "do not message", "don't message", "no"));
        return s;
    }

    /** Trimmed, lower-cased, de-duplicated, blanks dropped; null reads as empty. */
    private static List<String> normalised(List<String> words) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (words != null)
            for (String w : words)
                if (w != null && !w.isBlank()) out.add(w.trim().toLowerCase(Locale.ROOT));
        return List.copyOf(out);
    }

    private static void validate(CampaignSettingsView b, List<String> stopWords) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!knownZone(b.timezone())) errors.put("timezone", "is not a known time zone");
        if (b.defaultCountryCode() == null || !COUNTRY_CODE.matcher(b.defaultCountryCode()).matches())
            errors.put("defaultCountryCode", "must be 1 to 3 digits");
        if (b.workingDays() == null || b.workingDays().isEmpty())
            errors.put("workingDays", "needs at least one day");
        boolean startOk = b.startTime() != null && TIME.matcher(b.startTime()).matches();
        boolean endOk = b.endTime() != null && TIME.matcher(b.endTime()).matches();
        if (!startOk) errors.put("startTime", "must be HH:mm");
        if (!endOk) errors.put("endTime", "must be HH:mm");
        else if (startOk && b.endTime().compareTo(b.startTime()) <= 0)
            errors.put("endTime", "must be after startTime");
        atLeast(errors, "gapMinSeconds", b.gapMinSeconds(), 1);
        atLeast(errors, "gapMaxSeconds", b.gapMaxSeconds(), 1);
        atLeast(errors, "skipGapMinSeconds", b.skipGapMinSeconds(), 1);
        atLeast(errors, "skipGapMaxSeconds", b.skipGapMaxSeconds(), 1);
        atLeast(errors, "hourlyCap", b.hourlyCap(), 1);
        atLeast(errors, "matureCap", b.matureCap(), 1);
        atLeast(errors, "healthyCap", b.healthyCap(), 1);
        atLeast(errors, "restDays", b.restDays(), 1);
        atLeast(errors, "recontactDays", b.recontactDays(), 0);
        if (!errors.containsKey("gapMaxSeconds") && b.gapMaxSeconds() < b.gapMinSeconds())
            errors.put("gapMaxSeconds", "must be at least gapMinSeconds");
        if (!errors.containsKey("skipGapMaxSeconds") && b.skipGapMaxSeconds() < b.skipGapMinSeconds())
            errors.put("skipGapMaxSeconds", "must be at least skipGapMinSeconds");
        if (!validWarmup(b.warmupCaps()))
            errors.put("warmupCaps", "must start at day 1 and rise, below day 31, with caps of at least 1");
        if (stopWords.isEmpty() || stopWords.size() > 50 || stopWords.stream().anyMatch(w -> w.length() > 40))
            errors.put("stopWords", "needs 1 to 50 words of at most 40 characters");
        if (!errors.isEmpty()) throw new ValidationException(errors);
    }

    private static void atLeast(Map<String, String> errors, String field, int value, int min) {
        if (value < min) errors.put(field, "must be at least " + min);
    }

    private static boolean knownZone(String id) {
        if (id == null) return false;
        try {
            ZoneId.of(id);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean validWarmup(List<CampaignSettings.WarmupStep> steps) {
        if (steps == null || steps.isEmpty() || steps.get(0).fromDay() != 1) return false;
        int previous = 0;
        for (CampaignSettings.WarmupStep step : steps) {
            if (step == null || step.fromDay() <= previous || step.fromDay() >= 31 || step.cap() < 1) return false;
            previous = step.fromDay();
        }
        return true;
    }
}
