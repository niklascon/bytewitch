package decoders.Nemesys

data class AlignedSegment(
    val protocolA: Int,
    val protocolB: Int,
    val segmentIndexA: Int,
    val segmentIndexB: Int,
    val dissimilarity: Double
)

// class for sequence alignment of nemesys object
object NemesysSequenceAlignment {
    // main function for sequence alignment
    fun alignSegments(messages: Map<Int, NemesysParsedMessage>): List<AlignedSegment> {
        val alignments = mutableListOf<AlignedSegment>()
        val tresholdAlignedSegment = 0.17

        // get dissimilarity matrix (by using canberra-ulm dissimilarity)
        val sparseMatrixData = calcSparseSimilarityMatrix(messages, tresholdAlignedSegment)

        for ((pair, matrixS) in sparseMatrixData) {
            if (matrixS.isEmpty()) continue

            val (protoA, protoB) = pair

            // get maximum amount of segments in each protocol
            val maxA = matrixS.keys.maxOf { it.first } + 1
            val maxB = matrixS.keys.maxOf { it.second } + 1

            val gapPenalty = -1.0
            val matrixNW = calcNeedlemanWunschMatrix(maxA, maxB, matrixS, gapPenalty)

            // traceback
            var i = maxA
            var j = maxB
            while (i > 0 && j > 0) {
                val score = matrixNW[i][j]
                val diag = matrixNW[i - 1][j - 1]
                val up = matrixNW[i - 1][j]
                // val left = matrixNW[i][j - 1]

                val sim = matrixS[i - 1 to j - 1] ?: Double.NEGATIVE_INFINITY
                if (score == diag + sim) {
                    if (1.0 - sim < tresholdAlignedSegment) {
                        alignments.add(AlignedSegment(protoA, protoB, i - 1, j - 1, 1.0 - sim))
                    }

                    i--
                    j--
                } else if (score == up + gapPenalty) {
                    i--
                } else { // if (score == left + gapPenalty)
                    j--
                }
            }
        }

        return alignments
    }

    // calc Needleman-Wunsch Matrix NW
    fun calcNeedlemanWunschMatrix(m: Int, n:Int, matrixS: Map<Pair<Int, Int>, Double>, gapPenalty: Double): Array<DoubleArray> {
        val matrixNW = Array(m + 1) { DoubleArray(n + 1) }

        // init matrix
        for (i in 0..m) matrixNW[i][0] = i * gapPenalty
        for (j in 0..n) matrixNW[0][j] = j * gapPenalty

        // fill matrix
        for (i in 1..m) {
            for (j in 1..n) {
                // if no entry in matrixS so choose Double.NEGATIVE_INFINITY (because matrixS in a sparse Matrix)
                val sim = matrixS[i - 1 to j - 1] ?: Double.NEGATIVE_INFINITY

                val match = matrixNW[i - 1][j - 1] + sim
                // no 'mismatch' value needed because that's already included in matrixS by having a lower score
                val delete = matrixNW[i - 1][j] + gapPenalty
                val insert = matrixNW[i][j - 1] + gapPenalty
                matrixNW[i][j] = maxOf(match, delete, insert)
            }
        }

        return matrixNW
    }

    /**
     * calculate sparse dissimilarity matrix for field pairs between protocols
     *
     * @return Map< Pair(protocolA_ID, protocolB_ID) -> Map<Pair(segmentIndexA, segmentIndexB) -> similarityValue> >
     */
    private fun calcSparseSimilarityMatrix(
        messages: Map<Int, NemesysParsedMessage>,
        similarityThreshold: Double
    ): Map<Pair<Int, Int>, Map<Pair<Int, Int>, Double>> {
        val result = mutableMapOf<Pair<Int, Int>, MutableMap<Pair<Int, Int>, Double>>()

        // go through all messages
        for (i in messages.keys) {
            for (j in messages.keys) {
                if (i >= j) continue // do not compare messages twice

                // extract segments and bytes
                val messageA = messages[i] ?: continue
                val messageB = messages[j] ?: continue

                val segmentsA = messageA.segments
                val segmentsB = messageB.segments
                val bytesA = messageA.bytes
                val bytesB = messageB.bytes

                val simMap = mutableMapOf<Pair<Int, Int>, Double>()

                // compare all segments form A with all segments of B
                for (segmentAIndex in segmentsA.indices) {
                    // extract bytes from segmentA
                    val (startA, typeA) = segmentsA[segmentAIndex]
                    val endA = if (segmentAIndex + 1 < segmentsA.size) segmentsA[segmentAIndex + 1].offset else bytesA.size
                    val segmentBytesA = bytesA.sliceArray(startA until endA)

                    for (segmentBIndex in segmentsB.indices) {
                        // extract bytes from segmentB
                        val (startB, typeB) = segmentsB[segmentBIndex]
                        val endB = if (segmentBIndex + 1 < segmentsB.size) segmentsB[segmentBIndex + 1].offset else bytesB.size
                        val segmentBytesB = bytesB.sliceArray(startB until endB)

                        val dissim = canberraUlmDissimilarity(segmentBytesA, segmentBytesB, typeA, typeB)
                        val sim = 1.0 - dissim

                        if (sim >= similarityThreshold) {
                            // save matches for segmentA
                            simMap[segmentAIndex to segmentBIndex] = sim
                        }
                    }
                }

                result[i to j] = simMap
            }
        }

        return result
    }

    // Canberra Distance for segments of the same size
    fun canberraDistance(segmentA: ByteArray, segmentB: ByteArray): Double {
        var sum = 0.0
        for (i in segmentA.indices) {
            val ai = segmentA[i].toInt() and 0xFF
            val bi = segmentB[i].toInt() and 0xFF
            val denominator = ai + bi
            if (denominator != 0) {
                sum += kotlin.math.abs(ai - bi).toDouble() / denominator
            }
        }
        return sum
    }

    // Canberra-Ulm Dissimilarity for segments of different sizes (sliding window approach)
    fun canberraUlmDissimilarity(segmentS: ByteArray, segmentT: ByteArray, typeA: NemesysField, typeB: NemesysField): Double {
        val shortSegment = if (segmentS.size <= segmentT.size) segmentS else segmentT
        val longSegment = if (segmentS.size > segmentT.size) segmentS else segmentT

        var minD = Double.MAX_VALUE

        // if both segments are a payload length field so set canberra distance to 0
        if ((typeA == NemesysField.PAYLOAD_LENGTH_LITTLE_ENDIAN && typeB == NemesysField.PAYLOAD_LENGTH_LITTLE_ENDIAN)
            || (typeA == NemesysField.PAYLOAD_LENGTH_BIG_ENDIAN && typeB == NemesysField.PAYLOAD_LENGTH_BIG_ENDIAN)) {
            minD = 0.0
        } else {
            // sliding window to search for the lowest dissimilarity
            for (offset in 0..(longSegment.size - shortSegment.size)) {
                val window = longSegment.sliceArray(offset until (offset + shortSegment.size))
                val dC = canberraDistance(shortSegment, window) / shortSegment.size
                if (dC < minD) {
                    minD = dC
                }
            }
        }

        val r = (longSegment.size - shortSegment.size).toDouble() / longSegment.size
        val pf = 0.8 // hyper parameter to set the non-linear penalty

        val dm = (shortSegment.size.toDouble() / longSegment.size.toDouble()) * minD +
                r +
                (1 - minD) * r * (shortSegment.size / (longSegment.size * longSegment.size) - pf)

        return dm
    }
}