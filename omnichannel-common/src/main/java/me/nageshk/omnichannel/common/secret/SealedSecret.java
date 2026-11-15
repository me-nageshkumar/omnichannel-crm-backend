package me.nageshk.omnichannel.common.secret;

/** A secret sealed by {@link SecretBox}: the id of the key used, the IV and the ciphertext with its tag, all base64. */
public record SealedSecret(String keyId, String iv, String ciphertext) {}
