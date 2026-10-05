package com.example.techagent.voice;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.BidirectionalOutputPayloadPart;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamInput;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamResponseHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One Nova 2 Sonic conversation over the bidirectional stream (InvokeModelWithBidirectionalStream).
 * Microphone audio goes in, speech and transcripts come out, and when the model asks for the tool,
 * the {@code toolHandler} runs it and the result goes back into the same stream.
 *
 * <p>Adapted from the official AWS Java sample (aws-samples/amazon-nova-samples,
 * speech-to-speech/amazon-nova-2-sonic/sample-codes/websocket-java), without the WebSocket server
 * and the web UI.
 */
class NovaSonicSession implements AutoCloseable {

    /** What the session shows and plays. Implemented by the command line runner. */
    interface Listener {
        void transcript(String role, String text);
        void audio(byte[] pcm24kHz);
        void interrupted();
        void toolCall(String toolName);
        /** The stream is over; {@code error} is null on a normal close. */
        default void ended(String error) { }
    }

    private static final Logger log = LoggerFactory.getLogger(NovaSonicSession.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String promptName = UUID.randomUUID().toString();
    private final String audioContentName = UUID.randomUUID().toString();
    private final Sinks.Many<InvokeModelWithBidirectionalStreamInput> input = Sinks.many().unicast().onBackpressureBuffer();
    private final BlockingQueue<Boolean> turnsEnded = new LinkedBlockingQueue<>();
    // Tool runs off the stream thread: the model keeps receiving audio while the agent works.
    private final ExecutorService toolExecutor = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private final UnaryOperator<String> toolHandler;
    private final CompletableFuture<Void> stream;

    // State of the output stream, written by the SDK thread only.
    private String role = "";
    private String generationStage = "";
    private String toolUseId;
    private String toolInput;
    // A turn with a tool ends only once the model has spoken AFTER the tool result: an END_TURN
    // that closes a filler sentence ("Je regarde...") during the tool call must not end the turn.
    private final AtomicBoolean waitingForToolAnswer = new AtomicBoolean();
    private volatile boolean toolResultSent;

    NovaSonicSession(BedrockRuntimeAsyncClient client, String modelId, String voiceId, String systemPrompt,
            String toolName, String toolDescription, String toolInputSchema, UnaryOperator<String> toolHandler,
            Listener listener) {
        this.listener = listener;
        this.toolHandler = toolHandler;
        InvokeModelWithBidirectionalStreamRequest request = InvokeModelWithBidirectionalStreamRequest.builder()
                .modelId(modelId).build();
        this.stream = client.invokeModelWithBidirectionalStream(request, input.asFlux(), responseHandler());
        // End of the stream (normal close, 8 minute limit or error): the browser front end shows it.
        this.stream.whenComplete((ok, error) -> listener.ended(error == null ? null : error.getClass().getSimpleName()));

        // Opening sequence: session, prompt (voice + tool), system prompt, then the audio channel.
        send(VoiceEvents.sessionStart(1024, 0.0));
        send(VoiceEvents.promptStart(promptName, voiceId, toolName, toolDescription, toolInputSchema));
        String systemContent = UUID.randomUUID().toString();
        send(VoiceEvents.textContentStart(promptName, systemContent, "SYSTEM"));
        send(VoiceEvents.textInput(promptName, systemContent, systemPrompt));
        send(VoiceEvents.contentEnd(promptName, systemContent));
        send(VoiceEvents.audioContentStart(promptName, audioContentName));
    }

    /** Sends microphone audio (16 kHz, 16 bits, mono). Call it continuously, silence included. */
    void sendAudio(byte[] pcm16kHz, int length) {
        byte[] chunk = length == pcm16kHz.length ? pcm16kHz : Arrays.copyOf(pcm16kHz, length);
        send(VoiceEvents.audioInput(promptName, audioContentName, Base64.getEncoder().encodeToString(chunk)));
    }

    /** Waits until the assistant has finished speaking (END_TURN). Returns false on timeout or stream error. */
    boolean awaitAssistantTurn(Duration timeout) throws InterruptedException {
        return Boolean.TRUE.equals(turnsEnded.poll(timeout.toMillis(), TimeUnit.MILLISECONDS));
    }

    @Override
    public void close() {
        send(VoiceEvents.contentEnd(promptName, audioContentName));
        send(VoiceEvents.promptEnd(promptName));
        send(VoiceEvents.sessionEnd());
        input.tryEmitComplete();
        try {
            stream.get(10, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            log.debug("stream_close error={}", e.getClass().getSimpleName());
        }
        toolExecutor.shutdownNow();
    }

    /** Several threads write (microphone, tool): emissions are serialized. */
    private synchronized void send(String eventJson) {
        input.emitNext(InvokeModelWithBidirectionalStreamInput.chunkBuilder()
                .bytes(SdkBytes.fromUtf8String(eventJson)).build(), Sinks.EmitFailureHandler.FAIL_FAST);
    }

    private InvokeModelWithBidirectionalStreamResponseHandler responseHandler() {
        return InvokeModelWithBidirectionalStreamResponseHandler.builder()
                .subscriber(InvokeModelWithBidirectionalStreamResponseHandler.Visitor.builder()
                        .onChunk(this::onChunk)
                        .build())
                .onError(e -> {
                    log.error("nova_sonic_stream_error error={} message={}", e.getClass().getSimpleName(), e.getMessage());
                    turnsEnded.offer(Boolean.FALSE);
                })
                .build();
    }

    private void onChunk(BidirectionalOutputPayloadPart part) {
        JsonNode event = JSON.readTree(part.bytes().asString(StandardCharsets.UTF_8)).path("event");
        if (event.has("contentStart")) {
            JsonNode start = event.get("contentStart");
            role = start.path("role").asString("");
            if ("ASSISTANT".equals(role) && toolResultSent) {
                toolResultSent = false;
                waitingForToolAnswer.set(false);
            }
            String fields = start.path("additionalModelFields").asString("");
            // Assistant text comes twice: SPECULATIVE (about to be said, shown live), then FINAL.
            generationStage = fields.isEmpty() ? "" : JSON.readTree(fields).path("generationStage").asString("");
        }
        else if (event.has("textOutput")) {
            String text = event.get("textOutput").path("content").asString("");
            if ("USER".equals(role) || ("ASSISTANT".equals(role) && "SPECULATIVE".equals(generationStage))) {
                listener.transcript(role, text);
            }
        }
        else if (event.has("audioOutput")) {
            listener.audio(Base64.getDecoder().decode(event.get("audioOutput").path("content").asString("")));
        }
        else if (event.has("toolUse")) {
            JsonNode toolUse = event.get("toolUse");
            toolUseId = toolUse.path("toolUseId").asString();
            toolInput = toolUse.path("content").asString("{}");
            waitingForToolAnswer.set(true);
            listener.toolCall(toolUse.path("toolName").asString());
        }
        else if (event.has("contentEnd")) {
            JsonNode end = event.get("contentEnd");
            String type = end.path("type").asString("");
            String stopReason = end.path("stopReason").asString("");
            log.debug("content_end role={} type={} stopReason={}", role, type, stopReason);
            if ("TOOL".equals(type)) {
                runTool(toolUseId, toolInput);
            }
            else if ("INTERRUPTED".equals(stopReason)) {
                // The technician spoke over the assistant (barge-in): stop playback.
                listener.interrupted();
            }
            else if ("ASSISTANT".equals(role) && "TEXT".equals(type) && "END_TURN".equals(stopReason)
                    && !waitingForToolAnswer.get()) {
                turnsEnded.offer(Boolean.TRUE);
            }
        }
        else if (event.has("usageEvent")) {
            // Token counts only (no content): used to estimate the voice cost.
            log.info("nova_sonic_usage {}", event.get("usageEvent").path("details"));
        }
    }

    private void runTool(String id, String inputJson) {
        toolExecutor.submit(() -> {
            String result;
            try {
                result = toolHandler.apply(inputJson);
            }
            catch (RuntimeException e) {
                log.warn("voice_tool_failed error={}", e.getClass().getSimpleName());
                result = "{\"status\":\"ERROR\",\"answer\":\"Le service est momentanément indisponible.\"}";
            }
            String contentName = UUID.randomUUID().toString();
            // Set before sending: the model may start answering as soon as the result arrives.
            toolResultSent = true;
            send(VoiceEvents.toolResultStart(promptName, contentName, id));
            send(VoiceEvents.toolResult(promptName, contentName, result));
            send(VoiceEvents.contentEnd(promptName, contentName));
        });
    }
}
