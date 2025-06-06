import bitmage.fromHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import decoders.*

class NemesysSequenceAlignmentTests {

    /*@Test
    fun testSimpleAlignment() {
        val msgA = ByteWitch.getBytesFromInputEncoding("01 02 03 04 05 06 07 08")
        val msgB = ByteWitch.getBytesFromInputEncoding("01 02 03 0A 0B 06 07 08")

        val messages = mapOf(
            0 to msgA,
            1 to msgB
        )

        val segments = mapOf(
            0 to listOf(0 to NemesysField.UNKNOWN, 2 to NemesysField.UNKNOWN, 4 to NemesysField.UNKNOWN, 6 to NemesysField.UNKNOWN),
            1 to listOf(0 to NemesysField.UNKNOWN, 2 to NemesysField.UNKNOWN, 4 to NemesysField.UNKNOWN, 6 to NemesysField.UNKNOWN)
        )

        val aligned = NemesysSequenceAlignment.alignSegments(messages, segments)

        assertTrue(aligned.isNotEmpty(), "Alignment should find aligned segments")
        assertTrue(aligned.any { it.segmentIndexA == 0 && it.segmentIndexB == 0 }, "First segments should be aligned")
        assertTrue(aligned.any { it.segmentIndexA == 3 && it.segmentIndexB == 3 }, "Last segments should be aligned")
    }*/

    /*@Test
    fun testNoAlignmentDueToDissimilarity() {
        val msgA = hexBytes("FF FF FF FF")
        val msgB = hexBytes("00 00 00 00")

        val messages = mapOf(0 to msgA, 1 to msgB)
        val segments = mapOf(
            0 to listOf(0 to NemesysField.UNKNOWN, 2 to NemesysField.UNKNOWN),
            1 to listOf(0 to NemesysField.UNKNOWN, 2 to NemesysField.UNKNOWN)
        )

        val aligned = NemesysSequenceAlignment.alignSegments(messages, segments)
        assertTrue(aligned.isEmpty(), "Completely dissimilar messages should yield no alignment")
    }*/

    @Test
    fun testCanberraDistanceEqualSegments() {
        val segmentA = ByteWitch.getBytesFromInputEncoding("01 02 03")
        val segmentB = ByteWitch.getBytesFromInputEncoding("01 02 03")
        val dist = NemesysSequenceAlignment.canberraDistance(segmentA, segmentB)
        assertEquals(0.0, dist)
    }

    @Test
    fun testCanberraDistanceUnequalSegments() {
        val segmentA = ByteWitch.getBytesFromInputEncoding("02 04")
        val segmentB = ByteWitch.getBytesFromInputEncoding("00 01")
        val dist = NemesysSequenceAlignment.canberraDistance(segmentA, segmentB)
        assertEquals(1.6, dist)
    }

    @Test
    fun testCanberraUlmDissimilarityEqualLength() {
        val segmentA = ByteWitch.getBytesFromInputEncoding("00 00")
        val segmentB = ByteWitch.getBytesFromInputEncoding("00 00")
        val dissim = NemesysSequenceAlignment.canberraUlmDissimilarity(segmentA, segmentB)
        assertEquals(0.0, dissim)
    }

    @Test
    fun testCanberraUlmDissimilarityUnequalLength() {
        val segmentA = ByteWitch.getBytesFromInputEncoding("00 00 00")
        val segmentB = ByteWitch.getBytesFromInputEncoding("00 00")
        val dissim = NemesysSequenceAlignment.canberraUlmDissimilarity(segmentA, segmentB)
        assertEquals(0.0, dissim)
    }

    @Test
    fun testCanberraUlmDissimilarityEqualSegment() {
        val segmentA = ByteWitch.getBytesFromInputEncoding("00 00 11")
        val segmentB = ByteWitch.getBytesFromInputEncoding("00 00")
        val dissim = NemesysSequenceAlignment.canberraUlmDissimilarity(segmentA, segmentB)
        assertEquals(0.0, dissim)
    }

    @Test
    fun testCanberraUlmDissimilarityShiftedSegment() {
        val segmentA = ByteWitch.getBytesFromInputEncoding("01 02 03")
        val segmentB = ByteWitch.getBytesFromInputEncoding("00 01 02 03 04")
        val dissim = NemesysSequenceAlignment.canberraUlmDissimilarity(segmentA, segmentB)
        assertEquals(0.0, dissim)
    }

    @Test
    fun testCanberraUlmDissimilarityEqualsCanberraDistance() {
        val segmentA = ByteWitch.getBytesFromInputEncoding("A3 B7")
        val segmentB = ByteWitch.getBytesFromInputEncoding("C7 01")
        val dissim = NemesysSequenceAlignment.canberraUlmDissimilarity(segmentA, segmentB)
        val dist = NemesysSequenceAlignment.canberraDistance(segmentA, segmentB)
        assertEquals(dissim, dist/2)
    }

    @Test
    fun testCalcNeedlemanWunschMatrix() {
        val m = 2
        val n = 2
        val gapPenalty = -1.0

        // Sparse Similarity Matrix
        val matrixS = mapOf(
            0 to 0 to 0.9,  // high similarity
            1 to 1 to 0.8   // medium similarity
        )

        val matrix = NemesysSequenceAlignment.calcNeedlemanWunschMatrix(m, n, matrixS, gapPenalty)

        val expected = arrayOf(
            doubleArrayOf(0.0, -1.0, -2.0),
            doubleArrayOf(-1.0, 0.9, -0.1),
            doubleArrayOf(-2.0, -0.1, 1.7)
        )

        for (i in 0..m) {
            for (j in 0..n) {
                assertEquals(expected[i][j], matrix[i][j], absoluteTolerance = 0.0001)
            }
        }
    }
}
