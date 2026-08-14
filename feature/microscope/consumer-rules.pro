# Shipped with this module because the UVC engine lives here (ADR 0001 isolation rule):
# whoever consumes :feature:microscope inherits the rules its engine needs. The libuvc AAR
# carries no proguard.txt of its own, so nothing protects these members by default.

# The native layer resolves these callbacks by name — libUVCCamera.so carries the literals
# "Can't find IFrameCallback#onFrame", "…IButtonCallback#onButton" and
# "…IStatusCallback#onStatus". Let R8 rename them and a minified build still renders a
# preview (that path is native) while every capture times out and the hardware shutter
# goes dead — a failure no emulator and no debug build can reproduce.
-keep interface com.jiangdg.uvc.IFrameCallback { *; }
-keep interface com.jiangdg.uvc.IButtonCallback { *; }
-keep interface com.jiangdg.uvc.IStatusCallback { *; }

# A stale class name here fails silently: R8 treats -keep on a missing class as a no-op
# and warns about nothing, so the rules simply stop protecting anything. Verified against
# the shipped jni/arm64-v8a/libUVCCamera.so, whose string table contains com/jiangdg/uvc/
# UVCCamera, mNativePtr, onFrame, onButton and onStatus verbatim.

# Same story for the handle the native side looks up by name.
-keepclassmembers class com.jiangdg.uvc.UVCCamera {
    long mNativePtr;
}
