package com.secretchat.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/mfa")
public class MfaController {
  private final MfaService mfa;

  public MfaController(MfaService mfa) {
    this.mfa = mfa;
  }

  public record Code(@NotBlank @Size(max = 64) String code) {}

  @PostMapping("/setup")
  public MfaService.Setup setup(@AuthenticationPrincipal AuthPrincipal p) {
    return mfa.setup(p);
  }

  @PostMapping("/verify")
  public MfaService.State verify(
      @AuthenticationPrincipal AuthPrincipal p, @Valid @RequestBody Code code) {
    return mfa.verify(p, code.code());
  }

  @GetMapping
  public MfaService.State state(@AuthenticationPrincipal AuthPrincipal p) {
    return mfa.state(p);
  }

  @PostMapping("/replace-session")
  public MfaService.State replace(@AuthenticationPrincipal AuthPrincipal p) {
    return mfa.replace(p);
  }
}
