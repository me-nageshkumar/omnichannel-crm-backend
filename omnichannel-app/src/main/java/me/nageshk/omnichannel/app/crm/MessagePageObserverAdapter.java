package me.nageshk.omnichannel.app.crm;

import me.nageshk.omnichannel.crm.platform.ChannelKind;
import me.nageshk.omnichannel.crm.segment.MessagePageActivityRecorder;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.service.page.MessagePageObserver;
import org.springframework.stereotype.Component;

/** Message page opens and actions reach CRM timelines here; the channel account is the sending number. */
@Component
class MessagePageObserverAdapter implements MessagePageObserver {

    private final MessagePageActivityRecorder recorder;
    private final PhoneNumberRepository numbers;

    MessagePageObserverAdapter(MessagePageActivityRecorder recorder, PhoneNumberRepository numbers) {
        this.recorder = recorder;
        this.numbers = numbers;
    }

    @Override
    public void opened(PageActivity e) {
        recorder.recordOpened(e.workspaceId(), e.contactId(), e.channelAccountId(), kindOf(e), e.messageId(), e.at());
    }

    @Override
    public void acted(PageActivity e) {
        recorder.recordAction(e.workspaceId(), e.contactId(), e.channelAccountId(), kindOf(e), e.messageId(),
                e.kind(), e.id(), e.title(), e.latitude(), e.longitude(), e.at());
    }

    private ChannelKind kindOf(PageActivity e) {
        return numbers.findById(e.channelAccountId()).map(PhoneNumber::getConnectionType)
                .map(ChannelContactQueryAdapter::kindOf).orElse(ChannelKind.WHATSAPP_WEB);
    }
}
