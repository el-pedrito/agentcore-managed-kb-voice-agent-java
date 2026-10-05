package com.example.techagent.voice;

import com.example.techagent.agent.TechnicianAgent;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Voice in the browser (profile "voice-web"): the demo screen streams the microphone over a local
 * WebSocket, this handler relays it to Nova 2 Sonic and streams the spoken answer back.
 *
 * <p>Same tool and same text agent as the command line runner: every technical question goes
 * through the demo 2 agent and its grounding guardrail. Protocol:
 * <ul>
 *   <li>browser -> server: first a text frame {@code {"type":"start","intervention":"INT-...","voice":"florian"}},
 *       then binary frames of PCM 16 kHz, 16 bits, mono, little endian, sent continuously;
 *       {@code {"type":"stop"}} ends the conversation.</li>
 *   <li>server -> browser: binary frames of PCM 24 kHz (the spoken answer), and text events
 *       {@code ready}, {@code transcript}, {@code tool}, {@code agent} (the full agent response,
 *       same shape as /api/agent), {@code interrupted}, {@code ended}, {@code error}.</li>
 * </ul>
 * Local demo only: listens on 127.0.0.1, accepts the demo screen origin only, no authentication.
 */
@Component
@Profile("voice-web")
class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceWebSocketHandler.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern INTERVENTION_ID = Pattern.compile("INT-\\d{4}-\\d{4}");
    private static final Set<String> VOICES = Set.of("florian", "ambre");

    private final TechnicianAgent agent;
    private final String region;
    private final String modelId;
    private final String defaultVoice;
    private final String basePrompt;
    private final BedrockRuntimeAsyncClient client;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    VoiceWebSocketHandler(TechnicianAgent agent, @Value("${voice.region}") String region,
            @Value("${voice.model-id}") String modelId, @Value("${voice.voice-id}") String defaultVoice,
            @Value("classpath:prompts/voice-system-prompt.md") Resource systemPrompt) throws IOException {
        this.agent = agent;
        this.region = region;
        this.modelId = modelId;
        this.defaultVoice = defaultVoice;
        this.basePrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
        this.client = VoiceRunner.sonicClient(region);
    }

    private record Conversation(WebSocketSession ws, NovaSonicSession sonic) { }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("voice_ws_open region={}", region);
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) throws Exception {
        JsonNode msg = JSON.readTree(message.getPayload());
        String type = msg.path("type").asString("");
        if ("stop".equals(type)) {
            stop(raw.getId());
            raw.close(CloseStatus.NORMAL);
            return;
        }
        if (!"start".equals(type) || conversations.containsKey(raw.getId())) {
            return;
        }
        String interventionId = msg.path("intervention").asString("").strip().toUpperCase();
        if (!interventionId.isEmpty() && !INTERVENTION_ID.matcher(interventionId).matches()) {
            send(raw, event("error").put("message", "Numéro d'intervention attendu au format INT-AAAA-NNNN"));
            return;
        }
        String voice = msg.path("voice").asString(defaultVoice);
        if (!VOICES.contains(voice)) {
            voice = defaultVoice;
        }
        // Outgoing frames come from several threads (audio, tool, stream end): serialized by the decorator.
        WebSocketSession ws = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 4 * 1024 * 1024);
        String prompt = basePrompt.replace("{gender}", "ambre".equals(voice) ? "female" : "male")
                + VoiceRunner.intervention(interventionId);
        NovaSonicSession sonic = new NovaSonicSession(client, modelId, voice, prompt, VoiceRunner.TOOL_NAME,
                VoiceRunner.TOOL_DESCRIPTION, VoiceRunner.TOOL_SCHEMA, input -> askAgent(ws, input, interventionId),
                listener(ws));
        conversations.put(raw.getId(), new Conversation(ws, sonic));
        send(ws, event("ready").put("voice", voice).put("region", region));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Conversation c = conversations.get(session.getId());
        if (c == null) {
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] pcm = new byte[payload.remaining()];
        payload.get(pcm);
        c.sonic().sendAudio(pcm, pcm.length);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        stop(session.getId());
        log.info("voice_ws_closed code={}", status.getCode());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("voice_ws_error error={}", exception.getClass().getSimpleName());
        stop(session.getId());
    }

    /** Closing the Nova Sonic stream can take a few seconds: done off the WebSocket thread. */
    private void stop(String id) {
        Conversation c = conversations.remove(id);
        if (c != null) {
            Thread.ofVirtual().start(c.sonic()::close);
        }
    }

    /** The tool: demo 2 agent with its guardrail. The browser gets the full response, Nova Sonic the checked answer. */
    private String askAgent(WebSocketSession ws, String toolInputJson, String interventionId) {
        String question = JSON.readTree(toolInputJson).path("question").asString("");
        send(ws, event("agent_start").put("question", question));
        TechnicianAgent.AgentResponse response = agent.invoke(
                new TechnicianAgent.AgentRequest(question, interventionId.isBlank() ? null : interventionId), null);
        ObjectNode out = event("agent").put("question", question);
        out.set("response", JSON.valueToTree(response));
        send(ws, out);
        return JSON.writeValueAsString(new VoiceRunner.ToolAnswer(response.status().name(), response.answer()));
    }

    private NovaSonicSession.Listener listener(WebSocketSession ws) {
        return new NovaSonicSession.Listener() {
            public void transcript(String role, String text) {
                send(ws, event("transcript").put("role", role).put("text", text));
            }

            public void audio(byte[] pcm24kHz) {
                try {
                    if (ws.isOpen()) {
                        ws.sendMessage(new BinaryMessage(pcm24kHz));
                    }
                }
                catch (IOException | IllegalStateException e) {
                    log.debug("voice_audio_send_failed error={}", e.getClass().getSimpleName());
                }
            }

            public void interrupted() {
                send(ws, event("interrupted"));
            }

            public void toolCall(String toolName) {
                send(ws, event("tool").put("name", toolName));
            }

            public void ended(String error) {
                ObjectNode e = event("ended");
                if (error != null) {
                    e.put("error", error);
                }
                send(ws, e);
            }
        };
    }

    private static ObjectNode event(String type) {
        return JSON.createObjectNode().put("type", type);
    }

    private static void send(WebSocketSession ws, ObjectNode event) {
        try {
            if (ws.isOpen()) {
                ws.sendMessage(new TextMessage(JSON.writeValueAsString(event)));
            }
        }
        catch (IOException | IllegalStateException e) {
            log.debug("voice_event_send_failed error={}", e.getClass().getSimpleName());
        }
    }
}
