package com.example.techagent.voice;

import com.example.techagent.agent.TechnicianAgent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.http.ProtocolNegotiation;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Voice front end of the technician agent, from the command line (profile "voice", no web server).
 *
 * <p>Nova 2 Sonic listens and speaks. For any technical question it calls a single tool,
 * {@code askTechnicalAgent}, which runs the text agent of demo 2 in the same process: same
 * documentation, same intervention, same grounding guardrail. The voice model only speaks an answer
 * that the guardrail has already checked.
 *
 * <ul>
 *   <li>Microphone mode (default): talk, the answer plays on the speakers. Headset recommended,
 *       otherwise the assistant hears itself. Press Enter to stop.</li>
 *   <li>File mode ({@code --input=q1.wav,q2.wav --output=answers.wav}): plays recorded questions one
 *       after the other, for a repeatable test without a microphone.</li>
 * </ul>
 */
@Component
@Profile("voice")
class VoiceRunner implements ApplicationRunner {

    static final String TOOL_NAME = "askTechnicalAgent";
    static final String TOOL_DESCRIPTION = """
            Answers any technical question about the equipment of the current intervention (fault codes, \
            procedures, safety, settings, spare parts and their stock), from the manufacturers' documentation. \
            Call it for every technical question.""";
    static final String TOOL_SCHEMA = """
            {"type":"object","properties":{"question":{"type":"string",\
            "description":"The technician's question rewritten as one self-contained sentence in French, \
            with the fault code, part or symptom mentioned earlier in the conversation."}},\
            "required":["question"]}""";

    private static final AudioFormat MIC = new AudioFormat(VoiceEvents.INPUT_SAMPLE_RATE, 16, 1, true, false);
    private static final AudioFormat SPEAKER = new AudioFormat(VoiceEvents.OUTPUT_SAMPLE_RATE, 16, 1, true, false);
    // 100 ms of 16 kHz 16-bit mono audio per event.
    private static final int CHUNK_BYTES = 3_200;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern INTERVENTION_ID = Pattern.compile("INT-\\d{4}-\\d{4}");

    private final TechnicianAgent agent;
    private final String region;
    private final String modelId;
    private final String voiceId;
    private final String systemPrompt;

    VoiceRunner(TechnicianAgent agent, @Value("${voice.region}") String region, @Value("${voice.model-id}") String modelId,
            @Value("${voice.voice-id}") String voiceId,
            @Value("classpath:prompts/voice-system-prompt.md") Resource systemPrompt) throws IOException {
        this.agent = agent;
        this.region = region;
        this.modelId = modelId;
        this.voiceId = voiceId;
        // French agreement: the assistant's gender matches the voice (ambre is feminine, florian masculine).
        this.systemPrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8)
                .replace("{gender}", "ambre".equals(voiceId) ? "female" : "male");
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String interventionId = option(args, "intervention", "").strip().toUpperCase();
        // Validated before it goes into the system prompt.
        if (!interventionId.isEmpty() && !INTERVENTION_ID.matcher(interventionId).matches()) {
            throw new IllegalArgumentException("Intervention number expected as INT-AAAA-NNNN");
        }
        List<String> inputs = option(args, "input", "").isBlank() ? List.of() : List.of(option(args, "input", "").split(","));
        String output = option(args, "output", "answers.wav");

        ByteArrayOutputStream recorded = new ByteArrayOutputStream();
        SourceDataLine speaker = inputs.isEmpty() ? openSpeaker() : null;
        NovaSonicSession.Listener listener = listener(speaker, recorded);

        try (BedrockRuntimeAsyncClient client = sonicClient();
                NovaSonicSession session = new NovaSonicSession(client, modelId, voiceId,
                        systemPrompt + intervention(interventionId), TOOL_NAME, TOOL_DESCRIPTION, TOOL_SCHEMA,
                        input -> askAgent(input, interventionId), listener)) {
            if (inputs.isEmpty()) {
                talk(session);
            }
            else {
                replay(session, inputs);
            }
        }
        finally {
            if (speaker != null) {
                speaker.drain();
                speaker.close();
            }
        }
        if (!inputs.isEmpty()) {
            write(recorded.toByteArray(), new File(output));
            System.out.println("Spoken answers written to " + output);
        }
    }

    /** The tool: the text agent of demo 2, with its grounding guardrail. */
    String askAgent(String toolInputJson, String interventionId) {
        String question = JSON.readTree(toolInputJson).path("question").asString("");
        TechnicianAgent.AgentResponse response = agent.invoke(
                new TechnicianAgent.AgentRequest(question, interventionId.isBlank() ? null : interventionId), null);
        // Demo console only, so the spoken answer can be compared with the answer the guardrail
        // checked. A deployed version must not log the technician's text or the answers.
        System.out.println("  [agent] \"" + question + "\" -> " + response.status() + " " + response.toolCalls());
        System.out.println("  [agent answer, checked] " + response.answer().replace("\n", " "));
        return JSON.writeValueAsString(new ToolAnswer(response.status().name(), response.answer()));
    }

    record ToolAnswer(String status, String answer) { }

    /** The application, not the voice, chooses the work order: it is stated once in the system prompt. */
    static String intervention(String interventionId) {
        return interventionId.isBlank()
                ? "\n\n<intervention>Aucune intervention en cours : le modèle de l'équipement est inconnu.</intervention>"
                : "\n\n<intervention>Intervention en cours : " + interventionId + "</intervention>";
    }

    private void talk(NovaSonicSession session) throws LineUnavailableException, IOException {
        TargetDataLine mic = AudioSystem.getTargetDataLine(MIC);
        mic.open(MIC, CHUNK_BYTES * 4);
        AtomicBoolean running = new AtomicBoolean(true);
        Thread capture = null;
        try {
            mic.start();
            capture = Thread.ofVirtual().start(() -> {
                byte[] buffer = new byte[CHUNK_BYTES];
                while (running.get()) {
                    int n = mic.read(buffer, 0, buffer.length);
                    if (n > 0) {
                        session.sendAudio(buffer, n);
                    }
                }
            });
            System.out.println("Parlez. Entrée pour arrêter (une session dure 8 minutes au plus).");
            System.in.read();
        }
        finally {
            running.set(false);
            mic.stop();
            mic.close();
            if (capture != null) {
                try {
                    capture.join(1_000);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** File mode: each question at real-time pace, then silence until the assistant has finished. */
    private void replay(NovaSonicSession session, List<String> inputs) throws Exception {
        byte[] silence = new byte[CHUNK_BYTES];
        for (String file : inputs) {
            System.out.println("> " + file);
            try (AudioInputStream audio = open(new File(file.strip()))) {
                byte[] buffer = new byte[CHUNK_BYTES];
                int n;
                while ((n = audio.readNBytes(buffer, 0, buffer.length)) > 0) {
                    session.sendAudio(buffer, n);
                    Thread.sleep(100);
                }
            }
            // Keep streaming silence: Nova Sonic detects the end of the question, then answers.
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
            boolean answered = false;
            while (!answered && System.currentTimeMillis() < deadline) {
                session.sendAudio(silence, silence.length);
                answered = session.awaitAssistantTurn(Duration.ofMillis(100));
            }
            if (!answered) {
                // Stop rather than send the next question over a late answer.
                System.out.println("  (no answer within 60 s, stopping)");
                return;
            }
        }
    }

    /** WAV (any format, converted to 16 kHz mono) or raw .pcm already at 16 kHz, 16 bits, mono (Polly output). */
    private static AudioInputStream open(File file) throws Exception {
        if (file.getName().endsWith(".pcm")) {
            byte[] pcm = java.nio.file.Files.readAllBytes(file.toPath());
            return new AudioInputStream(new java.io.ByteArrayInputStream(pcm), MIC, pcm.length / MIC.getFrameSize());
        }
        return AudioSystem.getAudioInputStream(MIC, AudioSystem.getAudioInputStream(file));
    }

    private static NovaSonicSession.Listener listener(SourceDataLine speaker, ByteArrayOutputStream recorded) {
        return new NovaSonicSession.Listener() {
            public void transcript(String role, String text) {
                System.out.println(("USER".equals(role) ? "Technicien : " : "Assistant : ") + text);
            }

            public void audio(byte[] pcm) {
                if (speaker != null) {
                    speaker.write(pcm, 0, pcm.length);
                }
                else {
                    recorded.writeBytes(pcm);
                }
            }

            public void interrupted() {
                if (speaker != null) {
                    speaker.flush();
                }
            }

            public void toolCall(String toolName) {
                System.out.println("  [tool] " + toolName);
            }
        };
    }

    private BedrockRuntimeAsyncClient sonicClient() {
        return sonicClient(region);
    }

    /** Shared with the browser voice front end (VoiceWebSocketHandler). */
    static BedrockRuntimeAsyncClient sonicClient(String region) {
        // The bidirectional stream needs HTTP/2.
        return BedrockRuntimeAsyncClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .protocol(Protocol.HTTP2)
                        .protocolNegotiation(ProtocolNegotiation.ALPN)
                        // Longer than a session (about 8 minutes): a silent technician must not cut the stream.
                        .readTimeout(Duration.ofMinutes(9)))
                .build();
    }

    private static SourceDataLine openSpeaker() throws LineUnavailableException {
        SourceDataLine line = AudioSystem.getSourceDataLine(SPEAKER);
        line.open(SPEAKER);
        line.start();
        return line;
    }

    private static void write(byte[] pcm, File file) {
        try (AudioInputStream stream = new AudioInputStream(new java.io.ByteArrayInputStream(pcm), SPEAKER,
                pcm.length / SPEAKER.getFrameSize())) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String option(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.getFirst();
    }
}
