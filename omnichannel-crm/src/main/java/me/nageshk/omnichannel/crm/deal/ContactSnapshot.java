package me.nageshk.omnichannel.crm.deal;

/** The primary contact as it was when the deal was made; shown when the contact can no longer be read. */
public record ContactSnapshot(String name, String phone) {}
