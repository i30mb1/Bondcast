package n7.bondcast.settings

public enum class VideoCodec(val mime: String, val label: String) {
    H264("video/avc", "H.264"),
    H265("video/hevc", "H.265"),
}
