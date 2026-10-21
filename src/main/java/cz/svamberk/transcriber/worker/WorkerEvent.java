package cz.svamberk.transcriber.worker;

import java.util.Map;

/** Fields irrelevant to an event type are null. Created only after strict JSON validation. */
public record WorkerEvent(int schemaVersion, String type, Map<String,String> engineVersions,
                          Integer blockId, Double secondsProcessed, String code, String message) {}
