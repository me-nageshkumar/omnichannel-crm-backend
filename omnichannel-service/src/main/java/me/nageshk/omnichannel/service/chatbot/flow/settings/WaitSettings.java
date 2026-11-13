package me.nageshk.omnichannel.service.chatbot.flow.settings;

import java.time.Duration;

public record WaitSettings(int amount, WaitUnit unit) {

    public Duration duration() {
        if (unit == null) return Duration.ofMinutes(amount);
        return switch (unit) {
            case MINUTES -> Duration.ofMinutes(amount);
            case HOURS -> Duration.ofHours(amount);
            case DAYS -> Duration.ofDays(amount);
        };
    }
}
