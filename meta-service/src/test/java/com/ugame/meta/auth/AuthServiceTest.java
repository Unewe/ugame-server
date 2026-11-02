package com.ugame.meta.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ugame.meta.player.Player;
import com.ugame.meta.player.PlayerRepository;
import com.ugame.meta.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuthServiceTest {

    private PlayerRepository players;
    private TokenService tokens;
    private AuthService auth;

    @BeforeEach
    @SuppressWarnings("deprecation")
    void setUp() {
        players = mock(PlayerRepository.class);
        tokens = mock(TokenService.class);
        PasswordEncoder encoder = NoOpPasswordEncoder.getInstance(); // в тесте bcrypt не нужен
        auth = new AuthService(players, encoder, tokens);

        when(tokens.issue(any())).thenAnswer(inv -> {
            Player p = inv.getArgument(0);
            return Mono.just(new AuthDtos.TokenResponse("access", "refresh", 900, p.getId(), p.getUsername(),
                    p.isGuest()));
        });
    }

    @Test
    void registerCreatesPlayerAndIssuesTokens() {
        when(players.existsByUsernameIgnoreCase("alice")).thenReturn(Mono.just(false));
        when(players.save(any())).thenAnswer(inv -> {
            Player p = inv.getArgument(0);
            p.setId(1L);
            return Mono.just(p);
        });

        StepVerifier.create(auth.register(new AuthDtos.RegisterRequest("alice", null, "secret123")))
                .expectNextMatches(t -> t.playerId() == 1L && t.username().equals("alice"))
                .verifyComplete();
    }

    @Test
    void registerRejectsTakenUsername() {
        when(players.existsByUsernameIgnoreCase("alice")).thenReturn(Mono.just(true));

        StepVerifier.create(auth.register(new AuthDtos.RegisterRequest("alice", null, "secret123")))
                .expectErrorMatches(e -> e instanceof ApiException api && api.getCode().equals("username_taken"))
                .verify();
        verify(players, never()).save(any());
    }

    @Test
    void registerMapsUniqueIndexRaceToConflict() {
        when(players.existsByUsernameIgnoreCase("alice")).thenReturn(Mono.just(false));
        when(players.save(any())).thenReturn(Mono.error(new DuplicateKeyException("ux_players_username")));

        StepVerifier.create(auth.register(new AuthDtos.RegisterRequest("alice", null, "secret123")))
                .expectErrorMatches(e -> e instanceof ApiException api && api.getStatus() == HttpStatus.CONFLICT)
                .verify();
    }

    @Test
    void loginWithWrongPasswordFails() {
        when(players.findByUsernameIgnoreCase("alice")).thenReturn(Mono.just(
                Player.builder().id(1L).username("alice").passwordHash("right-password").build()));

        StepVerifier.create(auth.login(new AuthDtos.LoginRequest("alice", "wrong-password")))
                .expectErrorMatches(e -> e instanceof ApiException api && api.getCode().equals("invalid_credentials"))
                .verify();
    }

    @Test
    void loginUnknownUserFailsTheSameWay() {
        when(players.findByUsernameIgnoreCase("ghost")).thenReturn(Mono.empty());

        StepVerifier.create(auth.login(new AuthDtos.LoginRequest("ghost", "whatever")))
                .expectErrorMatches(e -> e instanceof ApiException api && api.getCode().equals("invalid_credentials"))
                .verify();
    }

    @Test
    void loginSucceeds() {
        when(players.findByUsernameIgnoreCase("alice")).thenReturn(Mono.just(
                Player.builder().id(1L).username("alice").passwordHash("secret123").build()));

        StepVerifier.create(auth.login(new AuthDtos.LoginRequest("alice", "secret123")))
                .expectNextMatches(t -> t.playerId() == 1L)
                .verifyComplete();
    }

    @Test
    void refreshWithUnknownTokenFails() {
        when(tokens.consumeRefreshToken("bad")).thenReturn(Mono.empty());

        StepVerifier.create(auth.refresh(new AuthDtos.RefreshRequest("bad")))
                .expectErrorMatches(e -> e instanceof ApiException api && api.getCode().equals("invalid_refresh_token"))
                .verify();
    }

    @Test
    void guestGetsGeneratedNameAndPassword() {
        when(players.save(any())).thenAnswer(inv -> {
            Player p = inv.getArgument(0);
            p.setId(7L);
            return Mono.just(p);
        });

        StepVerifier.create(auth.guest())
                .expectNextMatches(g -> g.playerId() == 7L && g.guest()
                        && g.username().matches("^Player_\\d{6}$")
                        && g.password() != null && g.password().length() >= 20)
                .verifyComplete();
    }

    @Test
    void guestRetriesWhenGeneratedNameIsTaken() {
        when(players.save(any()))
                .thenReturn(Mono.error(new DuplicateKeyException("ux_players_username")))
                .thenAnswer(inv -> {
                    Player p = inv.getArgument(0);
                    p.setId(8L);
                    return Mono.just(p);
                });

        StepVerifier.create(auth.guest())
                .expectNextMatches(g -> g.playerId() == 8L)
                .verifyComplete();
        verify(players, times(2)).save(any());
    }

    @Test
    void upgradeTurnsGuestIntoPlayer() {
        Player guest = Player.builder().id(5L).username("Player_123456").passwordHash("x").guest(true).build();
        Player registered = Player.builder().id(5L).username("alice").passwordHash("secret123").build();
        when(players.findById(5L)).thenReturn(Mono.just(guest), Mono.just(registered));
        when(players.existsByUsernameIgnoreCase("alice")).thenReturn(Mono.just(false));
        when(players.upgradeGuest(eq(5L), eq("alice"), any(), anyString())).thenReturn(Mono.just(1L));

        StepVerifier.create(auth.upgrade(5L, new AuthDtos.RegisterRequest("alice", null, "secret123")))
                .expectNextMatches(t -> t.playerId() == 5L && t.username().equals("alice") && !t.guest())
                .verifyComplete();
    }

    @Test
    void upgradeRejectsRegisteredPlayer() {
        when(players.findById(5L)).thenReturn(Mono.just(
                Player.builder().id(5L).username("alice").passwordHash("x").build()));

        StepVerifier.create(auth.upgrade(5L, new AuthDtos.RegisterRequest("alice", null, "secret123")))
                .expectErrorMatches(e -> e instanceof ApiException api && api.getCode().equals("already_registered"))
                .verify();
        verify(players, never()).upgradeGuest(anyLong(), anyString(), any(), anyString());
    }
}
