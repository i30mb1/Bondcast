package n7.bondcast.stream

internal const val USB_CAMERA_ID = "usb"

internal data class CameraOption(
    val id: String,
    val label: String,
    val isFront: Boolean,
)
