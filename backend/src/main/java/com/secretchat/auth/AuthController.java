package com.secretchat.auth;

import com.secretchat.protocol.AuthDtos.ChallengeResponse;
import com.secretchat.protocol.AuthDtos.LoginRequest;
import com.secretchat.protocol.AuthDtos.RefreshRequest;
import com.secretchat.protocol.AuthDtos.RegisterRequest;
import com.secretchat.protocol.AuthDtos.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService auth;
  private final ChallengeService challenges;

  public AuthController(AuthService auth, ChallengeService challenges) {
    this.auth = auth;
    this.challenges = challenges;
  }

  @PostMapping("/challenge")
  public ChallengeResponse challenge() {
    return challenges.issue();
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public TokenResponse register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
    return auth.register(req, http.getRemoteAddr());
  }

  @PostMapping("/login")
  public TokenResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
    return auth.login(req, http.getRemoteAddr());
  }

  @PostMapping("/refresh")
  public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
    return auth.refresh(req.refreshToken());
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@AuthenticationPrincipal AuthPrincipal principal) {
    auth.logout(principal.authSessionId());
  }
}
