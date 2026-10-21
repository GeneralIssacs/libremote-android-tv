package app.libremotetvservice.net

import app.libremotetvservice.net.LostBatch.Part
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the phone says about a batch whose connection broke, from what the receiver confirmed. */
class LostBatchTest {

    private val names = listOf("a-first.bin", "b-big.bin", "c-last.bin")

    @Test
    fun namesWhatArrivedWhatWasCutOffAndWhatWasNeverSent() {
        // Ticket 05's case 5: the phone's Wi-Fi went off in the middle of the big file.
        val lost = LostBatch(names, arrived = 1, started = 2)
        assertEquals(
            listOf(Part.Arrived(1, 3, "a-first.bin", "a-first.bin"), Part.CutOff("b-big.bin"), Part.Rest("c-last.bin", 1, started = false)),
            lost.parts,
        )
        assertEquals(1, lost.skip)
        assertEquals(2, lost.remaining)
    }

    @Test
    fun severalArrivedAndSeveralLeft() {
        val names = (1..10).map { "$it.jpg" }
        assertEquals(
            listOf(Part.Arrived(4, 10, "1.jpg", "4.jpg"), Part.CutOff("5.jpg"), Part.Rest("6.jpg", 5, started = false)),
            LostBatch(names, arrived = 4, started = 5).parts,
        )
    }

    @Test
    fun filesSentWholeButNotConfirmedDidNotArrive() {
        // Small files go out faster than the TV confirms them: the phone had begun the last one too.
        assertEquals(
            listOf(Part.Arrived(1, 3, "a-first.bin", "a-first.bin"), Part.CutOff("b-big.bin"), Part.Rest("c-last.bin", 1, started = true)),
            LostBatch(names, arrived = 1, started = 3).parts,
        )
    }

    @Test
    fun brokenBetweenTwoFiles() {
        // The first was confirmed, the second not yet begun: nothing was cut off.
        assertEquals(
            listOf(Part.Arrived(1, 3, "a-first.bin", "a-first.bin"), Part.Rest("b-big.bin", 2, started = false)),
            LostBatch(names, arrived = 1, started = 1).parts,
        )
    }

    @Test
    fun noneArrived() {
        val lost = LostBatch(names, arrived = 0, started = 1)
        assertEquals(listOf(Part.NoneArrived(3), Part.CutOff("a-first.bin"), Part.Rest("b-big.bin", 2, started = false)), lost.parts)
        assertEquals(0, lost.skip)
        assertEquals(3, lost.remaining)
    }

    @Test
    fun oneFile() {
        assertEquals(listOf(Part.CutOff("movie.mkv")), LostBatch(listOf("movie.mkv"), arrived = 0, started = 1).parts)
    }

    @Test
    fun brokenBeforeAnyFileSaysNothingMore() {
        assertEquals(emptyList<Part>(), LostBatch(names, arrived = 0, started = 0).parts)
        assertEquals(emptyList<Part>(), LostBatch(names, arrived = null, started = 0).parts)
    }

    @Test
    fun aReceiverThatDoesNotConfirmEachFile() {
        // Before 1.0.1: only how far the phone got is known, and Try again sends them all.
        val lost = LostBatch(names, arrived = null, started = 2)
        assertEquals(listOf(Part.Unconfirmed("b-big.bin", 2, 3)), lost.parts)
        assertEquals(0, lost.skip)
        assertEquals(3, lost.remaining)
        assertEquals(emptyList<Part>(), LostBatch(listOf("movie.mkv"), arrived = null, started = 1).parts)
    }
}
