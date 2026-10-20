package me.nageshk.omnichannel.template;

/** API view of a template's file: its GridFS id, original name, detected MIME type and size in bytes. */
public record MediaView(String mediaId, String fileName, String mimeType, long size) {}
