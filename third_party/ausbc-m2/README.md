# Vendored AUSBC artifacts

Checked-in Maven repository serving the two `com.github.jiangdongguo.AndroidUSBCamera`
modules that `libausbc:3.3.3` depends on but that are missing from JitPack: the JitPack
build of tag `3.3.3` fails in the `:libuvc:ndkClean` task (no NDK on JitPack's current
builders), so `libuvc` and `libnative` were never published for that tag — see
<https://jitpack.io/com/github/jiangdongguo/AndroidUSBCamera/3.3.3/build.log>.

Provenance (verified 2026-08-10):

| Artifact | Bytes taken from | Rationale |
| --- | --- | --- |
| `libuvc-3.3.3.aar` | JitPack build of commit `9b0843ff75` (2023-08-15) | Only cached libuvc build after tag `3.3.3`; source delta to the tag is a two-file bugfix (`USBMonitor.java` +1/-1, `UVCCamera.java` +21/-9). |
| `libnative-3.3.3.aar` | JitPack build of tag `3.2.7` | No cached build at or after `3.3.3` exists. `libnative` is only used by AUSBC's H264/AAC recording processors, which this app never invokes (single-frame capture via preview callback); the artifact exists to satisfy dependency resolution. |

POMs are the JitPack originals with the version rewritten to `3.3.3` and the
`published-with-gradle-metadata` marker removed (no `.module` files are vendored).

## Local patch: USBMonitor targetSdk-34 compliance

`libuvc-3.3.3.aar` additionally carries a recompiled `com.jiangdg.usb.USBMonitor`
(source from commit `9b0843ff75`, matching the AAR): upstream `register()` crashes
with `IllegalArgumentException` on targetSdk 34+ because it wraps an **implicit**
intent in a **mutable** `PendingIntent` and registers a receiver for a custom action
without an export flag. The patch makes the permission intent explicit
(`setPackage`), keeps `FLAG_MUTABLE` (UsbManager must attach the grant extras), and
registers the receiver with `RECEIVER_NOT_EXPORTED` on API 33+. Only the
`USBMonitor*.class` entries in `classes.jar` differ from the JitPack artifact.

Upstream license: Apache-2.0 (jiangdongguo/AndroidUSBCamera). Remove this repository
once the app moves off AUSBC or a complete upstream publication exists.
