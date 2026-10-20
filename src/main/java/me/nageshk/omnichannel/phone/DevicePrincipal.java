package me.nageshk.omnichannel.phone;

/** An extension authenticated by its device key: the number it may connect for. */
public record DevicePrincipal(String phoneNumberId, String customerId, String workspaceId, String number,
        String deviceKeyId, String brandName) {}
