package com.secretchat.auth;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Spring Security token wrapping an {@link AuthPrincipal}. Name = user id. */
public class PrincipalAuthentication extends AbstractAuthenticationToken {
  public static final String DEVICE_APPROVED = "DEVICE_APPROVED";

  private final AuthPrincipal principal;

  public PrincipalAuthentication(AuthPrincipal principal) {
    super(authorities(principal));
    this.principal = principal;
    setAuthenticated(true);
  }

  private static List<GrantedAuthority> authorities(AuthPrincipal p) {
    List<GrantedAuthority> list = new ArrayList<>();
    list.add(new SimpleGrantedAuthority("ROLE_USER"));
    if (p.deviceApproved()) {
      list.add(new SimpleGrantedAuthority(DEVICE_APPROVED));
    }
    return list;
  }

  @Override
  public Object getCredentials() {
    return "";
  }

  @Override
  public AuthPrincipal getPrincipal() {
    return principal;
  }

  @Override
  public String getName() {
    return principal.getName();
  }
}
