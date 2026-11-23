package com.trustbridge.Security.Actor;

import com.trustbridge.Domain.Entities.Users;
import com.trustbridge.Domain.Repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Turns the authenticated request into the user id that guards check.
 *
 * Controllers call this with the Authentication Spring gives them. The id comes from the JWT
 * principal (email) looked up in the database, never from the request body or a hardcoded value.
 * If no one is logged in, or the user no longer exists, it throws: we fail closed.
 */
@Component
@RequiredArgsConstructor
public class CurrentActorResolver {

    private final UserRepository userRepository;

    public UUID requireActorId(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("Authentication required");
        }

        return userRepository.findByEmail(authentication.getName())
                .map(Users::getId)
                .orElseThrow(() -> new AccessDeniedException("Unknown user"));
    }
}