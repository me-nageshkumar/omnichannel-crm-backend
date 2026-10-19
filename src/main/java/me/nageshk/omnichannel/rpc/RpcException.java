package me.nageshk.omnichannel.rpc;

/** An error reported by the extension/page for an RPC, or a transport failure while performing one. */
public class RpcException extends RuntimeException {

    public static final String METHOD_NOT_FOUND = "METHOD_NOT_FOUND";
    public static final String PAGE_ERROR = "PAGE_ERROR";
    public static final String CHAT_NOT_FOUND = "CHAT_NOT_FOUND";
    public static final String WHATSAPP_NOT_READY = "WHATSAPP_NOT_READY";
    public static final String DEVICE_DISCONNECTED = "DEVICE_DISCONNECTED";
    public static final String SEND_FAILED = "SEND_FAILED";

    private final String code;

    public RpcException(String code, String message) {
        super(message == null ? code : message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
