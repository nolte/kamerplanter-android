# Shipped with this module because the UVC engine lives here (ADR 0001 isolation rule):
# whoever consumes :feature:microscope inherits the rules its engine needs.
#
# Neither AUSBC AAR carries a proguard.txt, so nothing protects these members by default.

# The native layer resolves these three callbacks by name — libUVCCamera.so carries the
# literals "Can't find IFrameCallback#onFrame", "…IButtonCallback#onButton" and
# "…IStatusCallback#onStatus". Let R8 rename them and a minified build still renders a
# preview (that path is native) while every capture times out and the hardware shutter
# goes dead — a failure no emulator and no debug build can reproduce.
-keep interface com.jiangdg.uvc.IFrameCallback { *; }
-keep interface com.jiangdg.uvc.IButtonCallback { *; }
-keep interface com.jiangdg.uvc.IStatusCallback { *; }

# Same story for the handle the native side looks up by name.
-keepclassmembers class com.jiangdg.uvc.UVCCamera {
    long mNativePtr;
}

# UvcEngine reads this private field reflectively to reach the UVC status endpoint (the
# hardware shutter), which AUSBC's CameraUVC does not expose.
-keepclassmembers class com.jiangdg.ausbc.camera.CameraUVC {
    private com.jiangdg.uvc.UVCCamera mUvcCamera;
}
