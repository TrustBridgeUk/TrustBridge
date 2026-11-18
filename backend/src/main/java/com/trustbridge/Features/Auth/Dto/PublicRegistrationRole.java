package com.trustbridge.Features.Auth.Dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.trustbridge.Domain.Enums.UserRole;

import java.util.Locale;

public enum PublicRegistrationRole {
    FREELANCER(UserRole.role.FREELANCER),
    CLIENT(UserRole.role.CLIENT);

    private final UserRole.role userRole;

    PublicRegistrationRole(UserRole.role userRole) {
        this.userRole = userRole;
    }

    public UserRole.role getUserRole() {
        return userRole;
    }

    @JsonCreator
    public static PublicRegistrationRole from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Role is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Role must be FREELANCER or CLIENT");
        }
    }
}
