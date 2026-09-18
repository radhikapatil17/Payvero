package dev.payvero.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI payveroOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Payvero API")
                        .version("v1")
                        .description("""
                                Payvero — Distributed Digital Payment & Financial Ledger Platform.

                                Every payment transaction is recorded with double-entry accounting ledger entries,
                                enforcing immutable transaction records, idempotency, and auditability.
                                Amounts are represented in integer minor units (cents): `25000` is $250.00.

                                Obtain a JWT token from `POST /api/auth/login`, then use Authorize
                                to execute protected financial operations.""")
                        .license(new License().name("MIT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from /api/auth/login or /api/auth/refresh")));
    }
}
