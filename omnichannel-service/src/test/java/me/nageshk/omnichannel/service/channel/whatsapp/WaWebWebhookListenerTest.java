package me.nageshk.omnichannel.service.channel.whatsapp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.ChannelKind;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Change;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import me.nageshk.omnichannel.common.events.IngestionFailedException;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.service.channel.ChannelWebhookEventStore;
import me.nageshk.omnichannel.service.events.WhatsAppEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WaWebWebhookListenerTest {

    private static final long TS = 1_793_872_800_000L;
    private static final Instant CAPTURED = Instant.ofEpochMilli(TS);
    private static final ChannelContext CTX =
            new ChannelContext(ChannelKind.WHATSAPP_WEB, "cust-1", "ws-1", "pn-1", "919928088743");

    private final ObjectMapper mapper = new ObjectMapper();
    private final WaWebWebhookConverter converter = mock(WaWebWebhookConverter.class);
    private final ChannelWebhookEventStore store = mock(ChannelWebhookEventStore.class);
    private final WhatsAppWebhookProcessor processor = mock(WhatsAppWebhookProcessor.class);
    private final PhoneNumberRepository phones = mock(PhoneNumberRepository.class);
    private final WaWebWebhookListener listener = new WaWebWebhookListener(converter, store, processor, phones);

    private final JsonNode payload = mapper.readTree("""
            {"id": {"fromMe": false, "remote": "919912345678@c.us", "id": "A1",
                    "_serialized": "false_919912345678@c.us_A1"}, "body": "hi", "t": 1793872800}""");
    private final WebhookEvent event = WebhookEvent.of(CTX, List.of(new Change("messages", null)));

    @BeforeEach
    void number() {
        PhoneNumber n = new PhoneNumber();
        n.setId("pn-1");
        n.setNumber("+919928088743");
        when(phones.findById("pn-1")).thenReturn(Optional.of(n));
    }

    private WhatsAppEvent bridgeEvent(String name) {
        return new WhatsAppEvent(this, "pn-1", "ws-1", "cust-1", name, payload, 7L, TS);
    }

    @Test
    void aConvertedEventIsStoredThenProcessed() {
        when(converter.convert(CTX, "message_create", payload, CAPTURED)).thenReturn(List.of(event));

        listener.on(bridgeEvent("message_create"));

        InOrder order = inOrder(converter, store, processor);
        order.verify(converter).convert(CTX, "message_create", payload, CAPTURED);
        order.verify(store).save(CTX, event, payload, 7L, CAPTURED, 0);
        order.verify(processor).process(CTX, List.of(event));
    }

    @Test
    void anIngestionFailureReachesTheHandler() {
        when(converter.convert(CTX, "message_create", payload, CAPTURED)).thenReturn(List.of(event));
        IngestionFailedException failure = new IngestionFailedException("messages", "A1", new RuntimeException("db"));
        doThrow(failure).when(processor).process(CTX, List.of(event));

        assertThatThrownBy(() -> listener.on(bridgeEvent("message_create"))).isSameAs(failure);
    }

    @Test
    void anyOtherProcessingFailureIsLoggedAndAcknowledged() {
        when(converter.convert(CTX, "message_create", payload, CAPTURED)).thenReturn(List.of(event));
        doThrow(new IllegalStateException("subscriber failed")).when(processor).process(CTX, List.of(event));

        assertThatCode(() -> listener.on(bridgeEvent("message_create"))).doesNotThrowAnyException();
    }

    @Test
    void aStoreFailureDoesNotStopTheMessage() {
        when(converter.convert(CTX, "message_create", payload, CAPTURED)).thenReturn(List.of(event));
        when(store.save(any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("mongo down"));

        listener.on(bridgeEvent("message_create"));

        verify(processor).process(CTX, List.of(event));
    }

    @Test
    void anEchoAndItsStatusAreStoredInOrderThenProcessedTogether() {
        WebhookEvent echo = WebhookEvent.of(CTX, List.of(new Change("smb_message_echoes", null)));
        WebhookEvent status = WebhookEvent.of(CTX, List.of(new Change("messages", null)));
        when(converter.convert(CTX, "message_create", payload, CAPTURED)).thenReturn(List.of(echo, status));

        listener.on(bridgeEvent("message_create"));

        InOrder order = inOrder(store, processor);
        order.verify(store).save(CTX, echo, payload, 7L, CAPTURED, 0);
        order.verify(store).save(CTX, status, payload, 7L, CAPTURED, 1);
        order.verify(processor).process(CTX, List.of(echo, status));
        verify(processor, never()).process(any(), any(WebhookEvent.class));
    }

    @Test
    void anUnconvertibleEventIsLoggedAndAcknowledged() {
        when(converter.convert(CTX, "message_create", payload, CAPTURED))
                .thenThrow(new IllegalArgumentException("odd shape"));

        assertThatCode(() -> listener.on(bridgeEvent("message_create"))).doesNotThrowAnyException();

        verify(store).save(eq(CTX), isNull(), eq(payload), eq(7L), eq(CAPTURED), eq(0));
        verifyNoInteractions(processor);
    }

    @Test
    void anEventWithNoWebhookIsNeitherStoredNorProcessed() {
        when(converter.convert(CTX, "message_ack", payload, CAPTURED)).thenReturn(List.of());

        listener.on(bridgeEvent("message_ack"));

        verifyNoInteractions(store, processor);
    }

    @Test
    void aDeviceEventIsNotConverted() {
        listener.on(bridgeEvent("ready"));
        listener.on(bridgeEvent("change_state"));
        listener.on(bridgeEvent("disconnected"));

        verifyNoInteractions(converter, store, processor, phones);
    }

    @Test
    void theInboundOnlyMessageEventIsADuplicateOfMessageCreate() {
        listener.on(bridgeEvent("message"));

        verifyNoInteractions(converter, store, processor);
    }

    @Test
    void theNumberIsLookedUpOnceAndAMissingNumberStillConverts() {
        when(converter.convert(any(), any(), any(), any())).thenReturn(List.of());

        listener.on(bridgeEvent("message_create"));
        listener.on(bridgeEvent("message_ack"));
        listener.on(new WhatsAppEvent(this, "pn-unknown", "ws-1", "cust-1", "message_create", payload, null, null));

        verify(phones).findById("pn-1");
        verify(converter).convert(new ChannelContext(ChannelKind.WHATSAPP_WEB, "cust-1", "ws-1", "pn-unknown", null),
                "message_create", payload, null);
        verify(converter, never()).convert(eq(new ChannelContext(ChannelKind.WHATSAPP_WEB, "cust-1", "ws-1", "pn-1",
                null)), any(), any(), any());
    }
}
