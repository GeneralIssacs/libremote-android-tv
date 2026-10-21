package app.libremotetvservice.net

/**
 * A batch whose connection broke (TransferLostException), and what can honestly be said about it. Bytes this phone
 * wrote are no proof that they arrived, so only files the receiver confirmed count as arrived ([arrived], the first
 * ones of [names]); null when the receiver doesn't confirm each file (before 1.0.1). [started] files had begun to go
 * out, the last of them perhaps in part.
 */
data class LostBatch(val names: List<String>, val arrived: Int?, val started: Int) {

    /** The sentences that describe it, in order: empty when there is nothing more to say than "it broke". */
    sealed interface Part {
        /** [count] of [total] files arrived, [first] to [last]. */
        data class Arrived(val count: Int, val total: Int, val first: String, val last: String) : Part

        /** None of the [total] files arrived. */
        data class NoneArrived(val total: Int) : Part

        /** [name] was under way when the connection broke. */
        data class CutOff(val name: String) : Part

        /** The [count] files from [first] on didn't arrive; [started]: some of them went out, unconfirmed. */
        data class Rest(val first: String, val count: Int, val started: Boolean) : Part

        /** A receiver that doesn't confirm each file: the connection broke while sending [name], file [number] of [total]. */
        data class Unconfirmed(val name: String, val number: Int, val total: Int) : Part
    }

    /** How many files at the start of the batch Try again leaves out: those that arrived. */
    val skip get() = arrived ?: 0

    /** How many files Try again sends. */
    val remaining get() = names.size - skip

    val parts: List<Part>
        get() {
            val total = names.size
            if (started == 0) return emptyList()
            if (arrived == null) return if (total > 1) listOf(Part.Unconfirmed(names[started - 1], started, total)) else emptyList()
            val parts = mutableListOf<Part>()
            if (total > 1) parts += if (arrived == 0) Part.NoneArrived(total) else Part.Arrived(arrived, total, names[0], names[arrived - 1])
            var next = arrived
            if (started > arrived) parts += Part.CutOff(names[next++])
            if (next < total) parts += Part.Rest(names[next], total - next, started = started > next)
            return parts
        }
}
