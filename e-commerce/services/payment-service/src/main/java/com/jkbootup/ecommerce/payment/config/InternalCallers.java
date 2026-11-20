package com.jkbootup.ecommerce.payment.config;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.Set;

/**
 * Who may call {@code /internal}: another service, with a client-credentials token from the realm whose audience
 * names this service and whose authorised party ({@code azp}, the Keycloak client that asked for it) is on the list.
 * A customer's token never passes: its audience is the public API, not this service. The network policy still limits
 * which pods can connect at all; this is the second, cryptographic check.
 */
final class InternalCallers {

    private InternalCallers() {}

    static AuthorizationManager<RequestAuthorizationContext> only(String self, String... callers) {
        Set<String> allowed = Set.of(callers);
        return (authentication, context) -> new AuthorizationDecision(
                authentication.get() instanceof JwtAuthenticationToken token && allows(token.getToken(), self, allowed));
    }

    static boolean allows(Jwt jwt, String self, Set<String> allowed) {
        return jwt.getAudience() != null && jwt.getAudience().contains(self) && allowed.contains(jwt.getClaimAsString("azp"));
    }
}
