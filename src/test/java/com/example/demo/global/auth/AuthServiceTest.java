package com.example.demo.global.auth;

import com.example.demo.domain.user.domain.User;
import com.example.demo.domain.user.domain.UserRole;
import com.example.demo.domain.user.repository.UserRepository;
import com.example.demo.global.exception.BusinessException;
import com.example.demo.global.util.JwtUtil;
import com.example.demo.global.util.RedisUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AuthServiceTest {
    @Mock
    JwtUtil jwtUtil;

    @Mock
    RedisUtil redisUtil;

    @Mock
    UserRepository userRepository;

    @Mock
    AuthenticationManager authenticationManager;

    @InjectMocks
    AuthService authService;

    @Test
    void login_validToken_returnsNewTokenDto(){
        AuthRequest dto = new AuthRequest("username", "password");

        Authentication authentication = mock(Authentication.class);
        UserDetails userDetails = mock(UserDetails.class);

        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(userDetails.getAuthorities()).thenReturn((Collection) List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(userDetails.getUsername()).thenReturn("username");
        when(jwtUtil.generateAccessToken(any(), any())).thenReturn("access.token.value");
        when(jwtUtil.generateRefreshToken(any())).thenReturn("refresh.token.value");

        TokenDto result = authService.login(dto);

        assertThat(result.accessToken()).isEqualTo("access.token.value");
        assertThat(result.refreshToken()).isEqualTo("refresh.token.value");
    }

    @Test
    void refresh_invalidToken_throwsException(){
        String refreshToken = "refresh.token.value";
        when(jwtUtil.isTokenValid(refreshToken)).thenReturn(false);

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void refresh_tokenNotInRedis_throwsException(){
        String refreshToken = "refresh.token.value";
        when(jwtUtil.isTokenValid(refreshToken)).thenReturn(true);
        when(jwtUtil.extractUsername(refreshToken)).thenReturn("username");
        when(redisUtil.get("refresh-token:username")).thenReturn(null);

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void refresh_userNotFound_throwsException(){
        String refreshToken = "refresh.token.value";
        when(jwtUtil.isTokenValid(refreshToken)).thenReturn(true);
        when(jwtUtil.extractUsername(refreshToken)).thenReturn("username");
        when(redisUtil.get("refresh-token:username")).thenReturn(refreshToken);
        when(userRepository.findByUsernameAndDeletedVersion("username", 0L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void refresh_validToken_returnNewAccessToken(){
        String refreshToken = "refresh.token.value";
        User user = User.builder()
                .username("username")
                .role(UserRole.USER)
                .build();

        when(jwtUtil.isTokenValid(refreshToken)).thenReturn(true);
        when(jwtUtil.extractUsername(refreshToken)).thenReturn("username");
        when(redisUtil.get("refresh-token:username")).thenReturn(refreshToken);
        when(userRepository.findByUsernameAndDeletedVersion("username", 0L)).thenReturn(Optional.of(user));
        when(jwtUtil.generateAccessToken(any(), any())).thenReturn("new.access.token");

        TokenResponse result = authService.refresh(refreshToken);

        assertThat(result.accessToken()).isEqualTo("new.access.token");
    }

    @Test
    void logout_authHeaderIsNull_deleteRefreshToken(){
        String refreshToken = "refresh.token.value";
        String authHeader = null;

        when(jwtUtil.isTokenValid(refreshToken)).thenReturn(true);
        when(jwtUtil.extractUsername(refreshToken)).thenReturn("username");

        authService.logout(refreshToken, authHeader);

        verify(redisUtil, never()).set(any(), any(), anyLong());
        verify(redisUtil).delete("refresh-token:username");
    }

    @Test
    void logout_remainingTtlIsZero_DeleteRefreshToken(){
        String refreshToken = "refresh.token.value";
        String authHeader = "Bearer access.token.value";
        String accessToken = "access.token.value";

        when(jwtUtil.isTokenValid(accessToken)).thenReturn(true);
        when(jwtUtil.extractRemainingTtl(accessToken)).thenReturn(0L);
        when(jwtUtil.isTokenValid(refreshToken)).thenReturn(true);
        when(jwtUtil.extractUsername(refreshToken)).thenReturn("username");

        authService.logout(refreshToken, authHeader);

        verify(redisUtil, never()).set(any(), any(), anyLong());
        verify(redisUtil).delete("refresh-token:username");
    }

    @Test
    void logout_invalidRefreshToken_notDeleteRefreshToken(){
        String refreshToken = "refresh.token.value";
        String authHeader = "Bearer access.token.value";
        String accessToken = "access.token.value";

        when(jwtUtil.isTokenValid(accessToken)).thenReturn(true);
        when(jwtUtil.extractRemainingTtl(accessToken)).thenReturn(1L);
        when(jwtUtil.isTokenValid(refreshToken)).thenReturn(false);

        authService.logout(refreshToken, authHeader);

        verify(redisUtil).set("blacklist:" + accessToken, "logout", 1L);
        verify(redisUtil, never()).delete(any());
    }

    @Test
    void logout_validToken_blacklistAndDeleteRefreshToken(){
        String refreshToken = "refresh.token.value";
        String authHeader = "Bearer access.token.value";
        String accessToken = "access.token.value";

        when(jwtUtil.isTokenValid(accessToken)).thenReturn(true);
        when(jwtUtil.extractRemainingTtl(accessToken)).thenReturn(1L);
        when(jwtUtil.isTokenValid(refreshToken)).thenReturn(true);
        when(jwtUtil.extractUsername(refreshToken)).thenReturn("username");

        authService.logout(refreshToken, authHeader);

        verify(redisUtil).set("blacklist:" + accessToken, "logout", 1L);
        verify(redisUtil).delete("refresh-token:username");
    }
}
