package io.github.nolte.kamerplanter.feature.microscope

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

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
    private val isStreaming: () -> Boolean,
    private val onReady: (UsbDevice) -> Unit,
    private val onLost: (UsbDevice) -> Unit,
    private val onState: (MicroscopeState) -> Unit,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var registered = false

    /** The device a dialog is already open for, so it is not asked for twice. */
    private var pendingRequest: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = usbDeviceFrom(intent) ?: return
            when (intent.action) {
                // A hub can attach a second camera while the microscope streams. Claiming
                // it would drop a healthy stream into "waiting for permission" and hide
                // the controls, with no way back.
                UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    if (device.isVideoDevice() && !isStreaming()) claim(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (device.isVideoDevice()) lose(device)
                else -> resolvePermission(device, intent)
            }
        }
    }

    /** The attached UVC device, if any — also the device a fresh preview surface opens. */
    fun currentDevice(): UsbDevice? = usbManager.firstVideoDevice()

    fun start() {
        Log.i(TAG, "watching; attached video devices: ${describeDevices()}")
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
        pendingRequest = null
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
    }

    /** Opens straight away when already permitted, otherwise asks and waits for the answer. */
    fun claim(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            pendingRequest = null
            onReady(device)
            return
        }
        // start() and the arriving preview surface both land here for the same device;
        // asking twice stacks a second system dialog behind the first.
        if (pendingRequest == device.deviceName) {
            return
        }
        // Android answers a request for a device it has already refused without showing
        // the dialog again, until the device is re-attached.
        Log.i(TAG, "requesting USB permission for ${device.deviceName}")
        pendingRequest = device.deviceName
        onState(MicroscopeState.AwaitingPermission)
        usbManager.requestPermission(device, usbPermissionIntent(context))
    }

    private fun resolvePermission(device: UsbDevice, intent: Intent) {
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        Log.i(TAG, "USB permission answer for ${device.deviceName}: granted=$granted")
        pendingRequest = null
        if (granted) {
            onReady(device)
        } else {
            onState(MicroscopeState.Unavailable(UnavailableReason.PERMISSION_DENIED))
        }
    }

    /** Every attached device with its interface classes — the input to [isVideoDevice]. */
    private fun describeDevices(): String =
        usbManager.deviceList.values.joinToString(prefix = "[", postfix = "]") { device ->
            val classes = (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass }
            "${device.deviceName} ${device.vendorId}:${device.productId} classes=$classes"
        }

    private fun lose(device: UsbDevice) {
        onLost(device)
        if (currentDevice() == null) {
            onState(MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED))
        }
    }

    /**
     * AndroidX rather than a hand-rolled API check: below API 33 the platform has no
     * not-exported flag, and AndroidX closes that window by registering under a
     * signature-level permission instead. A foreign app forging the permission-granted
     * broadcast achieves nothing either way — libuvc re-checks the real grant before it
     * opens anything — but the receiver has no business being reachable at all.
     */
    private fun registerNotExported(receiver: BroadcastReceiver, filter: IntentFilter) {
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}

@Suppress("DEPRECATION")
private fun usbDeviceFrom(intent: Intent): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

private const val TAG = "MicroscopeCamera"
