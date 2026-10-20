package app.libremotetvservice.protocol

const val REMOTE_PORT = 6466

/**
 * Field numbers of the remote-control protocol on port 6466, as Google's TV Remote Service implements it.
 * Each message is a RemoteMessage whose single set field says what it carries. Names follow the service's own
 * (CONFIGURE, SET_ACTIVE, KEY_INJECT…); see PROTOCOL.md for the evidence.
 */
internal object Remote {
    // Both directions.
    const val CONFIGURE = 1
    const val SET_ACTIVE = 2
    const val PING_REQUEST = 8
    const val PING_RESPONSE = 9

    // TV to phone.
    const val ERROR = 3
    const val IME_FIELD = 20
    const val IME_SHOW = 21
    const val IME_TEXT = 22
    const val IME_SELECTION = 23
    const val POWER = 40
    const val VOLUME = 50

    // Phone to TV.
    const val KEY_INJECT = 10
    const val IME_BATCH_EDIT = 21
    const val SET_VOLUME_LEVEL = 50
    const val ADJUST_VOLUME_LEVEL = 51
    const val FIND_MY_REMOTE = 70
    const val APP_LINK = 90

    // KEY_INJECT directions; 0 is rejected and drops the connection.
    const val KEY_DOWN = 1
    const val KEY_UP = 2
    const val KEY_PRESS = 3

    // ADJUST_VOLUME_LEVEL directions.
    const val VOLUME_RAISE = 0
    const val VOLUME_LOWER = 1
    const val VOLUME_TOGGLE_MUTE = 2

    const val OS_ANDROID = 1
}

/** Feature bits negotiated in CONFIGURE and SET_ACTIVE. A phone may only ask for bits the TV offered. */
object Features {
    const val PING = 1
    const val KEYS = 2
    const val IME = 4
    const val VOICE = 8
    const val POWER = 32
    const val VOLUME = 64
    const val GAMEPAD = 256
    const val APP_LINKS = 512
    const val FIND_MY_REMOTE = 2048

    /** What this app uses. */
    const val WANTED = PING or KEYS or IME or POWER or VOLUME or APP_LINKS or FIND_MY_REMOTE
}

/** Errors a TV reports before it drops the connection (RemoteError.code). */
enum class TvError {
    UNSPECIFIED, ALREADY_CONFIGURED, NOT_READY, FEATURE_NOT_AVAILABLE, FEATURE_NOT_REQUESTED,
    UNKNOWN_CLIENT_MESSAGE, GENERIC_FAILURE, INVALID_ARGUMENT;

    companion object {
        fun of(code: Int) = entries.getOrElse(code) { UNSPECIFIED }
    }
}

/** How the phone introduces itself; the TV logs it. */
data class ClientInfo(
    val model: String,
    val manufacturer: String,
    val osVersion: String,
    val appName: String,
    val appVersion: String,
)

/**
 * What the TV said about itself when the connection opened (its CONFIGURE): the features it offers, and its
 * DeviceInfo, where the remote service puts its own package name and version as the "app".
 */
data class TvInfo(
    val features: Int,
    val model: String?,
    val manufacturer: String?,
    val osVersion: String?,
    val servicePackage: String? = null,
    val serviceVersion: String? = null,
) {
    fun supports(feature: Int) = features and feature != 0
}

/**
 * The TV's audio output. [level], [max] and [muted] are known only when the TV can set an absolute volume
 * ([absolute]); otherwise only relative steps work (or none, when the TV has no volume control at all).
 */
data class VolumeState(
    val deviceId: Int,
    val deviceName: String?,
    val control: Control,
    val level: Int,
    val max: Int,
    val muted: Boolean,
) {
    enum class Control { NONE, RELATIVE, ABSOLUTE }

    val absolute get() = control == Control.ABSOLUTE && max > 0
}

/**
 * The text field focused on the TV, or the app in front when no field is focused ([inputType] 0).
 * [inputType] and [imeOptions] are Android's EditorInfo values.
 */
data class TvTextField(
    val fieldId: Int,
    val inputType: Int,
    val imeOptions: Int,
    val hint: String?,
    val label: String?,
    val packageName: String?,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
) {
    val isEditable get() = inputType != 0
}
