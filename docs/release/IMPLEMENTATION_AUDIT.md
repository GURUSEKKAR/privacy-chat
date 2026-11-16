# Implementation audit

This is an implementation-author review, not independent security certification.

| Finding | Resolution | Verification |
| --- | --- | --- |
| Challenge and rate-limit maps could grow with arbitrary new identifiers | Fail closed at bounded capacity; expired or consumed entries release space; counters saturate | BoundedAuthenticationStateTest |
| A room session checked account/device but not the owning authenticated login | Bind reads and explicit session termination to auth_session_id; refresh retains that identity | AuthIntegrationTest |
| A stable MLS roster disagreement only produced a warning | Reject roster, duplicate-member and device-key disagreement; verify after full catch-up and before send | MlsLifecycleTest and encrypted client tests |
| Changed fingerprint verification could consume the sole outstanding KeyPackage before a retry | Check existing pins before claiming; require explicitly typed out-of-band confirmation in the UI | EncryptedChatTest |
| A desktop could be configured to send credentials to a remote HTTP server | Permit HTTPS origins and literal loopback HTTP only; reject URL credentials, query, fragment and non-root paths | ClientConfigurationTest |

| Legacy password hashes used a lower memory cost | Use Argon2id 19 MiB / two iterations and upgrade on successful login | AuthIntegrationTest |
| Direct HTTP development could listen beyond loopback and trust forwarded headers | Bind HTTP to loopback; explicitly bind TLS container; ignore forwarded headers by default | DeploymentSmokeTest and configuration review |
| Native packaging lacked a bundled-runtime check | Load three FXML screens, verify signing and JSON inside packaged runtime | RuntimeCheck |

Review requests and remaining human approvals are documented separately. Native bundles are local release candidates; signing/notarization and remote deployment trust are not inferred from a successful build.
