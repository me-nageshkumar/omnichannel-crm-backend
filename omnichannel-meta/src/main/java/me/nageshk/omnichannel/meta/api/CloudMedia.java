package me.nageshk.omnichannel.meta.api;

/** Media of a Cloud number at Meta: incoming files are fetched by Meta's media id, outgoing ones uploaded. */
public interface CloudMedia {

    record Downloaded(byte[] bytes, String mimeType, String filename) {}

    /**
     * @throws CloudMediaException when Meta refuses or the file is too large
     * @param maxBytes larger files are refused before they are fetched (Meta reports the size)
     */
    Downloaded download(String phoneNumberId, String metaMediaId, long maxBytes);

    /** Uploads a file for sending; returns Meta's media id (kept by Meta for 30 days). */
    String upload(String phoneNumberId, byte[] bytes, String mimeType, String filename);

    /** A media call that failed; {@code tooLarge} when the file is over the limit. */
    class CloudMediaException extends RuntimeException {
        private final boolean tooLarge;

        public CloudMediaException(String message, boolean tooLarge) {
            super(message);
            this.tooLarge = tooLarge;
        }

        public boolean tooLarge() {
            return tooLarge;
        }
    }
}
