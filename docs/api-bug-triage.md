# API Bug Triage for Zero-Copy Blur Pipeline

Verified analysis of reported bugs — separating real platform bugs from API misuse.

## Only Real Bug: Adreno EGLImage Double-Free

**Issue:** On Adreno GPUs, the driver auto-destroys the EGLImage when `glDeleteTextures` is called
on the associated texture. If the app also calls `eglDestroyImageKHR`, it's a double-free → SIGSEGV.

**Spec says:** EGLImage lifetime is independent of sibling resources. App MUST call `eglDestroyImageKHR`
separately. Adreno auto-destroying violates the EGL_KHR_image_base spec.

**Our mitigation:** Destroy EGLImage BEFORE deleting/respecifying the GL texture. Since we reuse the
input texture across frames (never delete mid-session), destroy EGLImage per-frame before the next
`eglCreateImageFromHardwareBuffer` call, never after texture cleanup in `release()`.

## Verified as NOT Bugs (Misuse or Expected Behavior)

| Issue | Verdict | Why |
|-------|---------|-----|
| GL_TEXTURE_2D rejected by some GPUs | Expected | Per-device extension variability. Our ext check handles this. |
| lockHardwareCanvas double-lock crash | Misuse | Must pair lock/unlock. Our try-finally does this. |
| SurfaceTexture.updateTexImage deadlock | Design limitation | BufferQueue exhaustion. Our single-frame pattern avoids it. |
| attachToGLContext after release crash | Misuse | Can't use released object. Our single-threaded lifecycle avoids this. |
| TextureView black first frame | Expected | BufferQueue empty until first swap. Cosmetic only. |
| setOpaque(false) transparency broken | Misuse | Must also glClear with alpha=0. We do this. |
| setDefaultBufferSize large dimension crash | Misuse | Exceeds GL_MAX_TEXTURE_SIZE. Our downsample keeps dimensions small. |
| Same-thread producer/consumer deadlock | Design limitation | Documented. Our one-swap-per-frame avoids it. |
| HardwareBuffer GL_TEXTURE_2D inconsistency | Expected | Same as GL_TEXTURE_2D extension variability. |
