package me.nageshk.omnichannel.common.inbox;

import me.nageshk.omnichannel.common.rpc.RpcException;

/** The phone no longer has the media of a message (or sent something unusable), or it is too large to fetch: 410. */
public class MediaUnavailableException extends RuntimeException {

    public static final String CODE = RpcException.MEDIA_UNAVAILABLE;
    public static final String MESSAGE = "Media is no longer available on the phone";

    public static final String TOO_LARGE_MESSAGE = "Media is too large to fetch from the phone";

    public MediaUnavailableException() {
        super(MESSAGE);
    }

    private MediaUnavailableException(String message) {
        super(message);
    }

    /** The file is over {@code ChatMediaService.MAX_SIZE}, so fetching it would only get the device disconnected. */
    public static MediaUnavailableException tooLarge() {
        return new MediaUnavailableException(TOO_LARGE_MESSAGE);
    }
}
