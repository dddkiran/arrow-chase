package com.arrowchase.ws;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI description of the server. OpenAPI only models HTTP, so the HTTP side is the actuator
 * endpoints, and the game protocol on {@code /ws} is described in the API description with its message types
 * published as schemas, so they can be browsed in Swagger UI next to it.
 */
@Configuration
public class ApiDocsConfig {

    private static final String DESCRIPTION = """
            Server-authoritative engine for the Arrow Chase shared multiplayer game.

            ## Game protocol (WebSocket)

            The game is played over a WebSocket, not HTTP, so it has no operations below. Connect to:

                ws(s)://<host>/ws?playerId=<id>&name=<display name>

            - `playerId`: 8–64 characters of `A-Z a-z 0-9 -`, generated and kept by the client. It identifies the \
            wallet, so reconnecting with the same id resumes the same balance and bets.
            - `name`: optional display name for the win feed, up to 20 characters.
            - The `Origin` header must match `ALLOWED_ORIGINS`, or the handshake is refused with 403.

            Every frame is a JSON object tagged by `type`. All money is integer **cents** and all multipliers are \
            integer **hundredths** (245 = 2.45×).

            | Direction | `type` | Schema |
            |---|---|---|
            | client → server | `placeBet` | `PlaceBet` |
            | server → that client, on connect | `welcome` | `Welcome` |
            | server → everyone, every step | `step` | `Step` |
            | server → that player | `betLocked` / `betRejected` | `BetLocked` / `BetRejected` |
            | server → that player | `betSettled` | `BetSettled` |
            | server → that client | `error` | `ErrorMessage` |

            `ClientMessage` and `ServerMessage` in the Schemas section are the unions of the two directions.

            A `placeBet` that repeats a `requestId` already answered gets the original answer again and is not \
            charged twice, so clients resend unconfirmed bets with the same id after a reconnect.
            """;

    @Bean
    OpenAPI arrowChaseOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Arrow Chase server")
                .version("0.1.0")
                .description(DESCRIPTION));
    }

    /** Adds the WebSocket message types to the document, since no HTTP operation references them. */
    @Bean
    OpenApiCustomizer webSocketMessageSchemas() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            var converters = ModelConverters.getInstance();
            for (Class<?> type : new Class<?>[]{Protocol.ClientMessage.class, Protocol.ServerMessage.class}) {
                Map<String, Schema> schemas = converters.readAll(new AnnotatedType(type));
                tagWithWireNames(schemas.get(type.getSimpleName()), type);
                schemas.forEach((name, schema) -> openApi.getComponents().addSchemas(name, schema));
            }
        };
    }

    /**
     * Swagger infers the discriminator but not its values, so it would show {@code type: "PlaceBet"}. Map each
     * {@code @JsonSubTypes} name ({@code placeBet}) to its schema and list the names as the allowed values.
     */
    @SuppressWarnings("unchecked")
    private static void tagWithWireNames(Schema<?> union, Class<?> type) {
        var mapping = new LinkedHashMap<String, String>();
        for (JsonSubTypes.Type subtype : type.getAnnotation(JsonSubTypes.class).value()) {
            mapping.put(subtype.name(), "#/components/schemas/" + subtype.value().getSimpleName());
        }
        union.setDiscriminator(new Discriminator().propertyName("type").mapping(mapping));
        ((Schema<String>) union.getProperties().get("type")).setEnum(List.copyOf(mapping.keySet()));
    }
}
