package io.github.nolte.kamerplanter.feature.microscope

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/** The permission broadcast is per-app, so the action carries the package name. */
internal fun usbPermissionAction(context: Context): String = "${context.packageName}.USB_PERMISSION"

/**
 * True when the device announces a USB Video interface.
 *
 * Deliberately narrower than the filter AUSBC ships, which also matches printers, mass
 * storage and CDC adapters — breadth that made a hub full of unrelated devices look like
 * microscopes.
 */
internal fun UsbDevice.isVideoDevice(): Boolean =
    (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }

/** The first attached UVC device, or null when none is plugged in. */
internal fun UsbManager.firstVideoDevice(): UsbDevice? =
    deviceList.values.firstOrNull { it.isVideoDevice() }

/**
 * The `PendingIntent` [UsbManager.requestPermission] answers through.
 *
 * Explicit via [Intent.setPackage] and still mutable: `targetSdk` 34 rejects a mutable
 * `PendingIntent` around an *implicit* intent, while `FLAG_IMMUTABLE` would stop
 * `UsbManager` attaching the grant extras this app has to read. Getting exactly this pair
 * wrong is what makes the engine's own USB monitor crash on modern Android.
 */
internal fun usbPermissionIntent(context: Context): PendingIntent {
    val intent = Intent(usbPermissionAction(context)).setPackage(context.packageName)
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    return PendingIntent.getBroadcast(context, 0, intent, flags)
}
