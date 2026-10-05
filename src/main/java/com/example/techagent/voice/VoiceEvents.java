package com.example.techagent.voice;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Input events of the Nova 2 Sonic bidirectional stream, built as JSON. Order of a session:
 * sessionStart, promptStart (voice, tools), system prompt, then audio until promptEnd, sessionEnd.
 * Reference: https://docs.aws.amazon.com/nova/latest/nova2-userguide/sonic-input-events.html
 */
final class VoiceEvents {

    static final int INPUT_SAMPLE_RATE = 16_000;
    static final int OUTPUT_SAMPLE_RATE = 24_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private VoiceEvents() { }

    static String sessionStart(int maxTokens, double temperature) {
        ObjectNode inference = JSON.createObjectNode().put("maxTokens", maxTokens).put("topP", 0.9)
                .put("temperature", temperature);
        return event("sessionStart", JSON.createObjectNode().set("inferenceConfiguration", inference));
    }

    /** Opens the prompt: spoken answer format, voice and the tool the model may call. */
    static String promptStart(String promptName, String voiceId, String toolName, String toolDescription,
            String toolInputSchema) {
        ObjectNode start = JSON.createObjectNode().put("promptName", promptName);
        start.putObject("textOutputConfiguration").put("mediaType", "text/plain");
        start.putObject("audioOutputConfiguration")
                .put("mediaType", "audio/lpcm").put("sampleRateHertz", OUTPUT_SAMPLE_RATE).put("sampleSizeBits", 16)
                .put("channelCount", 1).put("voiceId", voiceId).put("encoding", "base64").put("audioType", "SPEECH");
        start.putObject("toolUseOutputConfiguration").put("mediaType", "application/json");
        ObjectNode spec = start.putObject("toolConfiguration").putArray("tools").addObject().putObject("toolSpec");
        spec.put("name", toolName).put("description", toolDescription);
        spec.putObject("inputSchema").put("json", toolInputSchema);
        return event("promptStart", start);
    }

    static String textContentStart(String promptName, String contentName, String role) {
        ObjectNode start = content(promptName, contentName).put("type", "TEXT").put("role", role)
                .put("interactive", false);
        start.putObject("textInputConfiguration").put("mediaType", "text/plain");
        return event("contentStart", start);
    }

    static String textInput(String promptName, String contentName, String text) {
        return event("textInput", content(promptName, contentName).put("content", text));
    }

    /** Audio from the microphone: 16 kHz, 16 bits, mono, little endian. */
    static String audioContentStart(String promptName, String contentName) {
        ObjectNode start = content(promptName, contentName).put("type", "AUDIO").put("role", "USER")
                .put("interactive", true);
        start.putObject("audioInputConfiguration")
                .put("mediaType", "audio/lpcm").put("sampleRateHertz", INPUT_SAMPLE_RATE).put("sampleSizeBits", 16)
                .put("channelCount", 1).put("audioType", "SPEECH").put("encoding", "base64");
        return event("contentStart", start);
    }

    static String audioInput(String promptName, String contentName, String base64Audio) {
        return event("audioInput", content(promptName, contentName).put("content", base64Audio));
    }

    static String toolResultStart(String promptName, String contentName, String toolUseId) {
        ObjectNode start = content(promptName, contentName).put("type", "TOOL").put("role", "TOOL")
                .put("interactive", false);
        ObjectNode config = start.putObject("toolResultInputConfiguration").put("toolUseId", toolUseId).put("type", "TEXT");
        config.putObject("textInputConfiguration").put("mediaType", "text/plain");
        return event("contentStart", start);
    }

    static String toolResult(String promptName, String contentName, String resultJson) {
        return event("toolResult", content(promptName, contentName).put("content", resultJson));
    }

    static String contentEnd(String promptName, String contentName) {
        return event("contentEnd", content(promptName, contentName));
    }

    static String promptEnd(String promptName) {
        return event("promptEnd", JSON.createObjectNode().put("promptName", promptName));
    }

    static String sessionEnd() {
        return event("sessionEnd", JSON.createObjectNode());
    }

    private static ObjectNode content(String promptName, String contentName) {
        return JSON.createObjectNode().put("promptName", promptName).put("contentName", contentName);
    }

    private static String event(String type, ObjectNode body) {
        ObjectNode root = JSON.createObjectNode();
        root.putObject("event").set(type, body);
        return JSON.writeValueAsString(root);
    }
}
