package com.parkride.auth.service;

import com.parkride.auth.domain.Role;
import com.parkride.auth.domain.User;
import com.parkride.auth.dto.LoginRequest;
import com.parkride.auth.dto.RegisterRequest;
import com.parkride.auth.exception.UserAlreadyExistsException;
import com.parkride.auth.repository.RoleRepository;
import com.parkride.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService Unit Tests")
// "null" — Eclipse @NonNull false positives on Mockito willAnswer/inv.getArgument stubs.
@SuppressWarnings("null")
class AuthServiceTest {

    @Mock private UserRepository        userRepository;
    @Mock private RoleRepository        roleRepository;
    @Mock private TokenService          tokenService;
    @Mock private AuthenticationManager authenticationManager;

    // Use real BCrypt so we test actual password encoding behaviour
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    @InjectMocks
    private AuthService authService;

    // Must inject the real PasswordEncoder after @InjectMocks creates the service
    @BeforeEach
    void injectPasswordEncoder() throws Exception {
        var field = AuthService.class.getDeclaredField("passwordEncoder");
        field.setAccessible(true);
        field.set(authService, passwordEncoder);
    }

    // ── Registration tests ────────────────────────────────────────────────

    @Test
    @DisplayName("register: happy path creates user and returns tokens")
    void register_happyPath_createsUserAndReturnsTokens() {
        // arrange
        var request = RegisterRequest.builder()
                .email("alice@example.com")
                .password("Str0ngP@ss!")
                .firstName("Alice")
                .lastName("Smith")
                .build();

        Role userRole = Role.builder().id(UUID.randomUUID()).name(Role.RoleName.ROLE_USER).build();

        given(userRepository.existsByEmail(anyString())).willReturn(false);
        given(roleRepository.findByName(Role.RoleName.ROLE_USER)).willReturn(Optional.of(userRole));
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(tokenService.generateAccessToken(any())).willReturn("access.token.here");
        given(tokenService.generateAndPersistRefreshToken(any(), any())).willReturn("refresh.token.here");

        // act
        var response = authService.register(request, "TestDevice");

        // assert
        assertThat(response.getEmail()).isEqualTo("alice@example.com");
        assertThat(response.getFirstName()).isEqualTo("Alice");
        assertThat(response.getAccessToken()).isEqualTo("access.token.here");
        then(userRepository).should().save(any(User.class));
    }

    @Test
    @DisplayName("register: duplicate email throws UserAlreadyExistsException")
    void register_duplicateEmail_throwsConflict() {
        given(userRepository.existsByEmail(anyString())).willReturn(true);

        assertThatThrownBy(() -> authService.register(
                RegisterRequest.builder()
                        .email("dupe@example.com")
                        .password("pass1234!")
                        .firstName("Dupe")
                        .lastName("User")
                        .build(), null))
                .isInstanceOf(UserAlreadyExistsException.class)
                .hasMessageContaining("dupe@example.com");
    }

    // ── Login tests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("login: valid credentials reset failed attempts and return tokens")
    void login_validCredentials_returnsTokens() {
        Role userRole = Role.builder().id(UUID.randomUUID()).name(Role.RoleName.ROLE_USER).build();
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("bob@example.com")
                .passwordHash(passwordEncoder.encode("correct!"))
                .firstName("Bob")
                .lastName("Jones")
                .enabled(true)
                .failedLoginAttempts(2)
                .build();
        user.addRole(userRole);

        given(userRepository.findByEmail("bob@example.com")).willReturn(Optional.of(user));
        given(userRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(tokenService.generateAccessToken(any())).willReturn("access");
        given(tokenService.generateAndPersistRefreshToken(any(), any())).willReturn("refresh");

        var response = authService.login(LoginRequest.builder()
                .email("bob@example.com")
                .password("correct!")
                .build());

        assertThat(response.getEmail()).isEqualTo("bob@example.com");
        assertThat(user.getFailedLoginAttempts()).isZero();
    }

    @Test
    @DisplayName("login: wrong password increments failed attempts")
    void login_wrongPassword_incrementsFailedAttempts() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("carol@example.com")
                .passwordHash("hash")
                .enabled(true)
                .failedLoginAttempts(0)
                .build();

        given(userRepository.findByEmail("carol@example.com")).willReturn(Optional.of(user));
        willThrow(new BadCredentialsException("bad")).given(authenticationManager).authenticate(any());
        given(userRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> authService.login(
                LoginRequest.builder().email("carol@example.com").password("wrong").build()))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("login: 5 failures locks the account for 15 minutes")
    void login_fiveFailures_locksAccount() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("dave@example.com")
                .passwordHash("hash")
                .enabled(true)
                .failedLoginAttempts(4)   // one more failure will hit the limit
                .build();

        given(userRepository.findByEmail("dave@example.com")).willReturn(Optional.of(user));
        willThrow(new BadCredentialsException("bad")).given(authenticationManager).authenticate(any());
        given(userRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> authService.login(
                LoginRequest.builder().email("dave@example.com").password("wrong").build()))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(user.getLockedUntil()).isNotNull();
        assertThat(user.isAccountNonLocked()).isFalse();
    }
}
