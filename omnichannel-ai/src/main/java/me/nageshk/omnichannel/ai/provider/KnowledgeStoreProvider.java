package me.nageshk.omnichannel.ai.provider;

/** Implemented only by providers that can hold a brand's documents as a searchable store. */
public interface KnowledgeStoreProvider {

    /** Returns the provider's store id. */
    String createStore(AiCredential c, String name);

    /** A store that is already gone counts as deleted. */
    void deleteStore(AiCredential c, String storeId);

    /** Returns the provider's file id. */
    String uploadFile(AiCredential c, String fileName, String contentType, byte[] bytes);

    void attach(AiCredential c, String storeId, String fileId);

    KnowledgeFileState fileState(AiCredential c, String storeId, String fileId);

    /** A file that is already detached counts as done. */
    void detach(AiCredential c, String storeId, String fileId);

    /** A file that is already gone counts as deleted. */
    void deleteFile(AiCredential c, String fileId);
}
