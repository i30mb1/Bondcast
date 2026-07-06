package n7.bondcast.stream

public const val USB_CAMERA_ID = "usb"

public data class CameraOption(
    val id: String,
    val label: String,
    val isFront: Boolean,
)
