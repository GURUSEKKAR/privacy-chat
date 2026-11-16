package com.secretchat.devices;

import com.secretchat.auth.AuthPrincipal;
import com.secretchat.protocol.DeviceDtos.ApproveDeviceRequest;
import com.secretchat.protocol.DeviceDtos.DeviceView;
import com.secretchat.protocol.DeviceDtos.KeyPackageUpload;
import com.secretchat.protocol.DeviceDtos.KeyPackageUploadResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {
  private final DeviceService service;

  public DeviceController(DeviceService service) {
    this.service = service;
  }

  @GetMapping
  public List<DeviceView> list(@AuthenticationPrincipal AuthPrincipal p) {
    return service.list(p);
  }

  @PostMapping("/{deviceId}/approve")
  public DeviceView approve(
      @AuthenticationPrincipal AuthPrincipal p,
      @PathVariable UUID deviceId,
      @Valid @RequestBody ApproveDeviceRequest req) {
    return service.approve(p, deviceId, req.fingerprint());
  }

  @PostMapping("/{deviceId}/revoke")
  public DeviceView revoke(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID deviceId) {
    return service.revoke(p, deviceId);
  }

  @PutMapping("/me/key-packages")
  public KeyPackageUploadResult upload(
      @AuthenticationPrincipal AuthPrincipal p, @Valid @RequestBody KeyPackageUpload req) {
    return new KeyPackageUploadResult(service.replaceKeyPackages(p, req.keyPackages()));
  }
}
