package me.nageshk.omnichannel.inbox;

import me.nageshk.omnichannel.rpc.RpcException;

/** The phone no longer has the media of a message (or sent something unusable): 410. */
public class MediaUnavailableException extends RuntimeException {

    public static final String CODE = RpcException.MEDIA_UNAVAILABLE;
    public static final String MESSAGE = "Media is no longer available on the phone";

    public MediaUnavailableException() {
        super(MESSAGE);
    }
}
