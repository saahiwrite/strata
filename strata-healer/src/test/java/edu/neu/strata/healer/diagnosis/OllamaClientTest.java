package edu.neu.strata.healer.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OllamaClientTest {

    private MockRestServiceServer server;
    private OllamaClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OllamaClient(builder, "http://ollama:11434", "qwen2.5:0.5b");
    }

    @Test
    void sendsAJsonOnlyChatRequestAndReturnsTheReply() {
        server.expect(requestTo("http://ollama:11434/api/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value("qwen2.5:0.5b"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.format").value("json"))
                .andExpect(jsonPath("$.options.temperature").value(0))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("be careful"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("what broke?"))
                .andRespond(withSuccess("""
                        {"model": "qwen2.5:0.5b", "message": {"role": "assistant", "content": "{\\"action\\": \\"NONE\\"}"}, "done": true}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.complete("be careful", "what broke?")).contains("{\"action\": \"NONE\"}");
        server.verify();
    }

    @Test
    void serverErrorIsEmpty() {
        server.expect(requestTo("http://ollama:11434/api/chat")).andRespond(withServerError());

        assertThat(client.complete("s", "u")).isEmpty();
    }

    @Test
    void replyWithoutContentIsEmpty() {
        server.expect(requestTo("http://ollama:11434/api/chat"))
                .andRespond(withSuccess("{\"error\": \"model not found\"}", MediaType.APPLICATION_JSON));

        assertThat(client.complete("s", "u")).isEmpty();
    }

    @Test
    void unreachableServerIsEmpty() {
        OllamaClient offline = new OllamaClient(RestClient.builder(), "http://127.0.0.1:9", "m",
                java.time.Duration.ofSeconds(2));

        assertThat(offline.complete("s", "u")).isEmpty();
        assertThat(offline.model()).isEqualTo("m");
    }
}
