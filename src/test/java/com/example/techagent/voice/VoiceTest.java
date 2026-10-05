package com.example.techagent.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.techagent.agent.TechnicianAgent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class VoiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void promptStartDeclaresTheVoiceAndTheSingleTool() {
        JsonNode start = JSON.readTree(VoiceEvents.promptStart("p1", "florian", VoiceRunner.TOOL_NAME,
                VoiceRunner.TOOL_DESCRIPTION, VoiceRunner.TOOL_SCHEMA)).path("event").path("promptStart");

        assertThat(start.path("audioOutputConfiguration").path("voiceId").asString()).isEqualTo("florian");
        assertThat(start.path("audioOutputConfiguration").path("sampleRateHertz").asInt()).isEqualTo(24_000);
        JsonNode tool = start.path("toolConfiguration").path("tools").get(0).path("toolSpec");
        assertThat(tool.path("name").asString()).isEqualTo("askTechnicalAgent");
        // The schema is passed as a JSON string, and it is valid JSON.
        assertThat(JSON.readTree(tool.path("inputSchema").path("json").asString()).path("required").get(0).asString())
                .isEqualTo("question");
    }

    @Test
    void toolResultIsLinkedToTheToolUse() {
        JsonNode start = JSON.readTree(VoiceEvents.toolResultStart("p1", "c1", "tool-42")).path("event").path("contentStart");

        assertThat(start.path("role").asString()).isEqualTo("TOOL");
        assertThat(start.path("toolResultInputConfiguration").path("toolUseId").asString()).isEqualTo("tool-42");
    }

    @Test
    void theApplicationStatesTheIntervention() {
        assertThat(VoiceRunner.intervention("INT-2026-0412")).contains("Intervention en cours : INT-2026-0412");
        assertThat(VoiceRunner.intervention("")).contains("Aucune intervention en cours");
    }

    @Test
    void theToolRunsTheTextAgentOnTheInterventionOfTheSession() throws Exception {
        TechnicianAgent agent = mock(TechnicianAgent.class);
        when(agent.invoke(any(), isNull())).thenReturn(new TechnicianAgent.AgentResponse("Pression trop basse.",
                TechnicianAgent.Status.ANSWERED, List.of("getIntervention()"), List.of("notice.md"), null, null));
        VoiceRunner runner = new VoiceRunner(agent, "eu-north-1", "amazon.nova-2-sonic-v1:0", "florian",
                new ClassPathResource("prompts/voice-system-prompt.md"));

        // The model only provides the question: it cannot pick another work order.
        JsonNode result = JSON.readTree(runner.askAgent(
                "{\"question\":\"Code F28 : que faire ?\",\"interventionId\":\"INT-2026-0413\"}", "INT-2026-0412"));

        ArgumentCaptor<TechnicianAgent.AgentRequest> request = ArgumentCaptor.forClass(TechnicianAgent.AgentRequest.class);
        verify(agent).invoke(request.capture(), isNull());
        assertThat(request.getValue().interventionId()).isEqualTo("INT-2026-0412");
        assertThat(request.getValue().prompt()).isEqualTo("Code F28 : que faire ?");
        assertThat(result.path("status").asString()).isEqualTo("ANSWERED");
        assertThat(result.path("answer").asString()).isEqualTo("Pression trop basse.");
    }
}
