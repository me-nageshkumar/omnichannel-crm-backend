package me.nageshk.omnichannel.template;

import lombok.Getter;
import lombok.Setter;

/** A template's attached file: its GridFS id plus the details shown in the console. */
@Getter
@Setter
public class TemplateMediaRef {

    private String mediaId;
    private String fileName;
    private String mimeType;
    private long size;
}
