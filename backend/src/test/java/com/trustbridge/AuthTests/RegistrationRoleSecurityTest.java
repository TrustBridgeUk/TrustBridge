package com.trustbridge.AuthTests;

import com.trustbridge.Domain.Entities.Users;
import com.trustbridge.Domain.Enums.UserRole;
import com.trustbridge.Domain.Repositories.UserRepository;
import com.trustbridge.Features.Auth.Controller.API.AuthApiController;
import com.trustbridge.Features.Auth.GlobalExceptionHandler;
import com.trustbridge.Features.Auth.Service.LoginService;
import com.trustbridge.Features.Auth.Service.RegistrationService;
import com.trustbridge.Features.Notifications.Listeners.AuthEmail;
import com.trustbridge.Security.JwtService;
import com.trustbridge.Security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TRB 1.2: public registration must never create ADMIN (or MEDIATOR, or CLIENT_GUEST) accounts.
 *
 * Goes through the real controller, JSON binding, {@link GlobalExceptionHandler} and
 * {@link RegistrationService}; only the repository, encoder, email and event publisher are mocked.
 * The request body is raw JSON, so these tests do not depend on the type of RegistrationDTO.role.
 */
class RegistrationRoleSecurityTest {

    private UserRepository userRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);

        RegistrationService registrationService = new RegistrationService(
                userRepository,
                mock(PasswordEncoder.class),
                mock(AuthEmail.class),
                mock(ApplicationEventPublisher.class));

        AuthApiController controller = new AuthApiController(
                registrationService,
                mock(LoginService.class),
                mock(AuthenticationManager.class),
                mock(JwtService.class),
                mock(JwtTokenProvider.class));

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String registrationJson(String roleFragment) {
        return "{\"email\":\"new.user@example.com\","
                + "\"phoneNumber\":\"07494874632\","
                + "\"password\":\"password123\","
                + "\"firstName\":\"New\","
                + "\"lastName\":\"User\""
                + roleFragment
                + "}";
    }

    private static String roleField(String role) {
        return ",\"role\":\"" + role + "\"";
    }

    private void register(String json, org.springframework.test.web.servlet.ResultMatcher expectedStatus)
            throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(expectedStatus);
    }

    // ---------------------------------------------------------------------------------------
    // Rejected roles: 400 with a message, and nothing is saved
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"admin", "ADMIN", "Admin", " admin", "mediator", "MEDIATOR",
            "client_guest", "CLIENT_GUEST", "bogus", ""})
    void registering_withARoleOutsideTheAllowList_isRejectedWith400_andSavesNothing(String role)
            throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson(roleField(role))))
                .andExpect(status().isBadRequest())
                // the sign-up page shows data.message to the user
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(userRepository, never()).save(any(Users.class));
    }

    @Test
    void registering_withNoRole_isRejectedWith400_andSavesNothing() throws Exception {
        register(registrationJson(""), status().isBadRequest());

        verify(userRepository, never()).save(any(Users.class));
    }

    @Test
    void registering_withANullRole_isRejectedWith400_andSavesNothing() throws Exception {
        register(registrationJson(",\"role\":null"), status().isBadRequest());

        verify(userRepository, never()).save(any(Users.class));
    }

    // ---------------------------------------------------------------------------------------
    // Allowed roles still work. The lowercase ones are what the sign-up page sends today
    // (they pass before the fix and guard against breaking the frontend). The uppercase ones
    // assume the case-insensitive design and are expected to fail until the fix is in.
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "freelancer, FREELANCER",
            "client,     CLIENT",
            "FREELANCER, FREELANCER",
            "CLIENT,     CLIENT"
    })
    void registering_withAnAllowedRole_savesThatRole(String requested, String expected) throws Exception {
        register(registrationJson(roleField(requested)), status().isOk());

        ArgumentCaptor<Users> saved = ArgumentCaptor.forClass(Users.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getUserRole()).isEqualTo(UserRole.role.valueOf(expected));
    }
}