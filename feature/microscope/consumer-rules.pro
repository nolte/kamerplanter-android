# Shipped with this module because the UVC engine lives here (ADR 0001 isolation rule):
# whoever consumes :feature:microscope inherits the rules its engine needs. The libuvc AAR
# carries no proguard.txt of its own, so nothing protects these members by default.

# The native layer resolves these callbacks by name — libUVCCamera.so carries the literals
# "Can't find IFrameCallback#onFrame", "…IButtonCallback#onButton" and
# "…IStatusCallback#onStatus". Let R8 rename them and a minified build still renders a
# preview (that path is native) while every capture times out and the hardware shutter
# goes dead — a failure no emulator and no debug build can reproduce.
-keep interface com.serenegiant.usb.IFrameCallback { *; }
-keep interface com.serenegiant.usb.IButtonCallback { *; }
-keep interface com.serenegiant.usb.IStatusCallback { *; }

# Same story for the handle the native side looks up by name.
-keepclassmembers class com.serenegiant.usb.UVCCamera {
    long mNativePtr;
}
