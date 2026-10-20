package app.libremotetvservice.data

import app.libremotetvservice.protocol.TvInfo

/**
 * Whether a paired device is a television or a streaming box, which decides the remote's right-hand column: a TV gets
 * Channel +, Menu and Channel −; a box, which has no channels, gets Search, Menu and Apps. Detected from the maker and
 * model the device reports when it connects ([detect]); the TV picker can override it per TV (TvDetails.chooseKind).
 */
enum class TvKind {
    TV,
    BOX;

    companion object {
        /**
         * Streaming boxes and sticks, by what they report as maker and model (Android's Build.MANUFACTURER and
         * Build.MODEL). Both match case-insensitively at the start; a rule without a model covers every device of a
         * maker that makes no TVs. To add a device, add a line.
         */
        private val BOXES = listOf(
            Box(maker = "NVIDIA"), // SHIELD Android TV, every generation
            Box(maker = "Google", model = "Chromecast"), // Chromecast with Google TV, 4K and HD
            Box(maker = "Google", model = "Google TV Streamer"),
            Box(model = "ADT-"), // ADT-1 and ADT-3 developer boxes
            Box(maker = "Xiaomi", model = "MIBOX"), // Mi Box 3, Mi Box S (MIBOX4)
            Box(maker = "onn"), // Walmart's onn. Google TV boxes and sticks
            Box(maker = "Homatics"),
            Box(maker = "Formuler"),
            Box(maker = "Dynalink"),
            Box(maker = "TiVo"), // TiVo Stream 4K
            Box(maker = "SEI Robotics"), // set-top boxes for TV operators
            Box(maker = "Sagemcom"),
            Box(maker = "Kaon"),
            Box(maker = "Askey"),
        )

        /** Words that, as a whole word in a model name, only boxes and sticks use ("onn. 4K Streaming Box", "TV Stick"). */
        private val BOX_WORDS = Regex("\\b(box|stick|streamer|dongle|set-top)\\b", RegexOption.IGNORE_CASE)

        /** TV unless the reported maker and model clearly describe a box: a wrong guess only shows channel keys. */
        fun detect(info: TvInfo?): TvKind {
            val maker = info?.manufacturer?.trim().orEmpty()
            val model = info?.model?.trim().orEmpty()
            val box = BOXES.any { it.matches(maker, model) } || BOX_WORDS.containsMatchIn(model)
            return if (box) BOX else TV
        }
    }

    private class Box(val maker: String? = null, val model: String? = null) {
        fun matches(maker: String, model: String) =
            (this.maker == null || maker.startsWith(this.maker, ignoreCase = true)) &&
                (this.model == null || model.startsWith(this.model, ignoreCase = true))
    }
}
