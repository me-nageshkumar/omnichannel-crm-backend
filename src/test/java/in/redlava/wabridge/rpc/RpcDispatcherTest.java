package in.redlava.wabridge.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.redlava.wabridge.ws.DeviceSession;
import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class RpcDispatcherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private WebSocketSession wsSession;
    private DeviceSession device;
    private RpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        wsSession = mock(WebSocketSession.class);
        when(wsSession.getId()).thenReturn("s1");
        when(wsSession.isOpen()).thenReturn(true);
        device = new DeviceSession("demo", wsSession, mapper);
        Iterator<String> ids = List.of("id-1", "id-2", "id-3").iterator();
        dispatcher = new RpcDispatcher(Duration.ofSeconds(5), ids::next);
    }

    @Test
    void callSendsRequestFrameAndCompletesOnMatchingResponse() throws Exception {
        JsonNode params = mapper.createObjectNode().put("chatId", "1@c.us");
        CompletableFuture<JsonNode> future = dispatcher.call(device, "sendMessage", params);

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession).sendMessage(sent.capture());
        Envelope frame = mapper.readValue(sent.getValue().getPayload(), Envelope.class);
        assertThat(frame.type()).isEqualTo(Envelope.REQUEST);
        assertThat(frame.id()).isEqualTo("id-1");
        assertThat(frame.method()).isEqualTo("sendMessage");
        assertThat(frame.params().get("chatId").asText()).isEqualTo("1@c.us");
        assertThat(future).isNotDone();

        JsonNode result = mapper.createObjectNode().put("ok", true);
        boolean matched = dispatcher.complete(Envelope.response("id-1", result));

        assertThat(matched).isTrue();
        assertThat(future.get(1, TimeUnit.SECONDS).get("ok").asBoolean()).isTrue();
    }

    @Test
    void errorResponseCompletesExceptionallyWithRpcException() {
        CompletableFuture<JsonNode> future = dispatcher.call(device, "sendMessage", null);
        dispatcher.complete(Envelope.errorResponse("id-1", RpcException.CHAT_NOT_FOUND, "no chat"));

        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(RpcException.class)
                .satisfies(e -> assertThat(((RpcException) e).code()).isEqualTo(RpcException.CHAT_NOT_FOUND));
    }

    @Test
    void unknownResponseIdIsIgnored() {
        assertThat(dispatcher.complete(Envelope.response("nope", null))).isFalse();
    }

    @Test
    void callTimesOut() {
        RpcDispatcher fast = new RpcDispatcher(Duration.ofMillis(50), () -> "t1");
        CompletableFuture<JsonNode> future = fast.call(device, "getState", null);

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(TimeoutException.class);
        assertThat(fast.complete(Envelope.response("t1", null))).isFalse();
    }

    @Test
    void failAllFailsPendingCallsForUser() {
        CompletableFuture<JsonNode> f1 = dispatcher.call(device, "a", null);
        CompletableFuture<JsonNode> f2 = dispatcher.call(device, "b", null);

        int failed = dispatcher.failAll("demo", RpcException.DEVICE_DISCONNECTED);

        assertThat(failed).isEqualTo(2);
        assertThat(f1).isCompletedExceptionally();
        assertThat(f2).isCompletedExceptionally();
        assertThatThrownBy(f1::join).cause().isInstanceOf(RpcException.class)
                .satisfies(e -> assertThat(((RpcException) e).code()).isEqualTo(RpcException.DEVICE_DISCONNECTED));
    }

    @Test
    void sendFailureCompletesExceptionally() throws IOException {
        doThrow(new IOException("closed")).when(wsSession).sendMessage(any());
        CompletableFuture<JsonNode> future = dispatcher.call(device, "a", null);

        assertThatThrownBy(future::join).cause().isInstanceOf(RpcException.class)
                .satisfies(e -> assertThat(((RpcException) e).code()).isEqualTo(RpcException.SEND_FAILED));
    }
}
