package me.nageshk.omnichannel.service.page;

/** Fetches one URL for the link address check (spec 2026-11-18 link addresses §5.4); never follows a redirect. */
public interface PagesProbe {

    String NO_ANSWER = "NO_ANSWER";
    String BAD_CERTIFICATE = "BAD_CERTIFICATE";
    String NO_SUCH_NAME = "NO_SUCH_NAME";
    String REFUSED_ADDRESS = "REFUSED_ADDRESS";

    /** {@code failure} is one of the constants when no answer came, else null with the status and (at most 1 KB of) body. */
    record Result(int status, String body, String failure) {
    }

    Result get(String url);
}
