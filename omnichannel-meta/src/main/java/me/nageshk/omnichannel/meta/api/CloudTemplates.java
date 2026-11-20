package me.nageshk.omnichannel.meta.api;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Template;
import me.nageshk.omnichannel.common.channel.whatsapp.message.TemplateSnapshot;

/** Imported Meta templates, for the senders in the host (spec 2026-11-20 §5.6). */
public interface CloudTemplates {

    record Built(Template template, String renderedBody, TemplateSnapshot snapshot) {}

    /** The templates of the number's WABA; {@code sendableOnly}: approved and supported. */
    List<CloudTemplateInfo> forNumber(String workspaceId, String phoneNumberId, boolean sendableOnly);

    /** @throws me.nageshk.omnichannel.common.api.NotFoundException if the brand has no such template */
    CloudTemplateInfo get(String workspaceId, String id);

    /** The blanks of the template that {@code values} leaves empty, in template order. */
    List<String> missing(CloudTemplateInfo template, Map<String, String> values);

    /** Meta's {@code template} object for the values, the body as the customer reads it, and the thread's snapshot. */
    Built build(CloudTemplateInfo template, Map<String, String> values, String headerMetaMediaId);

    void markUsed(String id);
}
