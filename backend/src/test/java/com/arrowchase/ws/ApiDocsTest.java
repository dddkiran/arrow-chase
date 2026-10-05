package com.arrowchase.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiDocsTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void openApiDocumentDescribesActuatorAndWebSocketMessages() {
        JsonNode doc = http.getForObject("/v3/api-docs", JsonNode.class);

        assertThat(doc.at("/info/title").asText()).isEqualTo("Arrow Chase server");
        assertThat(doc.at("/paths").has("/actuator/health")).isTrue();
        assertThat(doc.at("/components/schemas")).isNotNull();
        for (String schema : new String[]{"ClientMessage", "PlaceBet", "ServerMessage", "Welcome", "Step",
                "BetLocked", "BetRejected", "BetSettled", "ErrorMessage", "BetDto"}) {
            assertThat(doc.at("/components/schemas").has(schema)).as(schema).isTrue();
        }
        // The discriminator uses the wire names from @JsonSubTypes, not the Java class names.
        assertThat(doc.at("/components/schemas/ClientMessage/discriminator/mapping/placeBet").asText())
                .isEqualTo("#/components/schemas/PlaceBet");
        assertThat(doc.at("/components/schemas/ServerMessage/discriminator/mapping").size()).isEqualTo(6);
    }

    @Test
    void swaggerUiIsServed() {
        assertThat(http.getForEntity("/swagger-ui/index.html", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
