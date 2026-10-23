package me.nageshk.omnichannel.db.template;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** The type-specific fields of a template; boxed so the ones a type does not use stay null and are not stored. */
@Getter
@Setter
public class TemplateContent {

    private String body;
    private Boolean linkPreview;
    private String caption;
    private Boolean voiceNote;
    private Double latitude;
    private Double longitude;
    private String name;
    private String address;
    private String fullName;
    private String phone;
    private String organization;
    private String email;
    private String question;
    private List<String> options;
    private Boolean allowMultiple;
}
