# App-specific ProGuard/R8 rules. Library-specific rules ship with their AARs.

# AusbcMicroscopeCamera reads CameraUVC's private `mUvcCamera` reflectively to reach the
# UVC status endpoint (hardware shutter and zoom buttons), which AUSBC does not expose.
-keepclassmembers class com.jiangdg.ausbc.camera.CameraUVC {
    private com.jiangdg.uvc.UVCCamera mUvcCamera;
}
