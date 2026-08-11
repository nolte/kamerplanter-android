package io.github.nolte.kamerplanter.feature.microscope

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Watches for the UVC device and carries the USB permission, using nothing but the
 * platform [UsbManager].
 *
 * Doing this here rather than through the engine's own monitor is what keeps
 * `USBMonitor.register()` — which crashes on `targetSdk` 34 — out of the app entirely.
 *
 * [onReady] fires with a device the app is permitted to open, on the main thread.
 */
internal class UsbAttachmentWatcher(
    private val context: Context,
    private val onReady: (UsbDevice) -> Unit,
    private val onLost: (UsbDevice) -> Unit,
    private val onState: (MicroscopeState) -> Unit,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = usbDeviceFrom(intent) ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (device.isVideoDevice()) claim(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (device.isVideoDevice()) lose(device)
                else -> resolvePermission(device, intent)
            }
        }
    }

    /** The attached UVC device, if any — also the device a fresh preview surface opens. */
    fun currentDevice(): UsbDevice? = usbManager.firstVideoDevice()

    fun start() {
        if (!registered) {
            val filter = IntentFilter(usbPermissionAction(context)).apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            registerNotExported(receiver, filter)
            registered = true
        }
        val device = currentDevice()
        if (device == null) {
            onState(MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED))
        } else {
            claim(device)
        }
    }

    fun stop() {
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
    }

    /** Opens straight away when already permitted, otherwise asks and waits for the answer. */
    fun claim(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            onReady(device)
        } else {
            onState(MicroscopeState.AwaitingPermission)
            usbManager.requestPermission(device, usbPermissionIntent(context))
        }
    }

    private fun resolvePermission(device: UsbDevice, intent: Intent) {
        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            onReady(device)
        } else {
            onState(MicroscopeState.Unavailable(UnavailableReason.PERMISSION_DENIED))
        }
    }

    private fun lose(device: UsbDevice) {
        onLost(device)
        if (currentDevice() == null) {
            onState(MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED))
        }
    }

    private fun registerNotExported(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }
}

@Suppress("DEPRECATION")
private fun usbDeviceFrom(intent: Intent): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
