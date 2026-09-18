package dev.payvero.auth;

import dev.payvero.auth.dto.AuthResponse;
import dev.payvero.auth.dto.CurrentUserResponse;
import dev.payvero.auth.dto.LoginRequest;
import dev.payvero.auth.dto.RefreshTokenRequest;
import dev.payvero.auth.dto.RegisterRequest;
import dev.payvero.api.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Authentication", description = "Registration, sign-in, and refresh token rotation")
@SecurityRequirements  // these endpoints are the way you obtain a token, so none is required
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtProperties jwtProperties;

    public AuthController(AuthService authService, JwtProperties jwtProperties) {
        this.authService = authService;
        this.jwtProperties = jwtProperties;
    }

    @Operation(summary = "Register a new account",
            description = "Creates the user and returns a token pair immediately, so no second sign-in is needed.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Registered"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "That email is already registered",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/register")
    ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        TokenPair tokens = authService.register(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(tokens));
    }

    @Operation(summary = "Sign in",
            description = """
                    An unknown email and a wrong password return an identical response, so the
                    endpoint cannot be used to discover which addresses are registered.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed in"),
            @ApiResponse(responseCode = "401", description = "Invalid email or password",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/login")
    ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        TokenPair tokens = authService.login(request.email(), request.password());
        return ResponseEntity.ok(toResponse(tokens));
    }

    @Operation(summary = "Rotate a refresh token",
            description = """
                    Spends the presented token and issues a successor in the same session
                    lineage. Presenting an already-spent token is treated as theft: the entire
                    lineage is revoked, so a stolen token can never be used silently.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rotated"),
            @ApiResponse(responseCode = "401", description = "Unknown, expired, or already-used token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/refresh")
    ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        TokenPair tokens = authService.refresh(request.refreshToken());
        return ResponseEntity.ok(toResponse(tokens));
    }

    /**
     * Always 204, including for a token that was never issued. Reporting "no
     * such token" would turn logout into an oracle for guessing valid tokens,
     * and a client has nothing useful to do with the distinction anyway.
     */
    @Operation(summary = "Who am I",
            description = """
                    Resolves the caller from the token, server-side. Clients can decode the JWT
                    themselves for display, but should treat this as the source of truth: a claim
                    read by the client is not an authorization decision.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The authenticated caller"),
            @ApiResponse(responseCode = "401", description = "Missing, expired or invalid token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me")
    CurrentUserResponse me(@AuthenticationPrincipal UUID userId) {
        return CurrentUserResponse.from(authService.requireUser(userId));
    }

    @Operation(summary = "Sign out",
            description = """
                    Revokes the token's whole session lineage. Always 204, including for a token
                    that was never issued, so logout cannot be used to test whether one is valid.

                    Note that an already-issued access token stays valid until it expires; the
                    short TTL is the mitigation for that, not a denylist.""")
    @ApiResponse(responseCode = "204", description = "Signed out, or the token was already unknown")
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private AuthResponse toResponse(TokenPair tokens) {
        return AuthResponse.bearer(tokens, jwtProperties.accessTokenTtl().toSeconds());
    }
}
