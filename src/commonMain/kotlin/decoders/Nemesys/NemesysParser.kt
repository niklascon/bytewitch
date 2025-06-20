package decoders.Nemesys

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln

class NemesysParser {
    // this finds all segment boundaries and returns a nemesys object that can be called to get the html code
    fun parse(bytes: ByteArray, msgIndex: Int): NemesysParsedMessage {
        val segments = findSegmentBoundaries(bytes)
        return NemesysParsedMessage(segments, bytes, msgIndex)
    }

    // count how often every segment exists
    fun countSegmentValues(messages: List<NemesysParsedMessage>, minSegmentLength: Int = 2): Map<ByteArray, Int> {
        val segmentValueCounts = mutableMapOf<ByteArray, Int>()

        for (msg in messages) {
            for ((index, segment) in msg.segments.withIndex()) {
                val start = segment.offset
                val end = msg.segments.getOrNull(index + 1)?.offset ?: msg.bytes.size
                val segmentBytes = msg.bytes.sliceArray(start until end)

                // filter some segments
                if (segmentBytes.size < minSegmentLength) continue
                if (segmentBytes.all { it == 0.toByte() }) continue

                // check for existing key
                val existingKey = segmentValueCounts.keys.find { it.contentEquals(segmentBytes) }

                if (existingKey != null) {
                    segmentValueCounts[existingKey] = segmentValueCounts.getValue(existingKey) + 1
                } else {
                    segmentValueCounts[segmentBytes.copyOf()] = 1
                }
            }
        }

        return segmentValueCounts
    }



    // Split segments if one segment of another message appears quite often
    fun cropDistinct(messages: List<NemesysParsedMessage>): List<NemesysParsedMessage> {
        // count how often every segment exists
        val segmentValueCounts = countSegmentValues(messages)

        // only keep segments that occur in more than 10% of the message
        val threshold = (messages.size * 0.1).toInt().coerceAtLeast(1)
        val frequentValues = segmentValueCounts.filterValues { it >= threshold }.keys

        // refine messages
        return messages.map { msg ->
            val newSegments = mutableListOf<NemesysSegment>()

            // go through each segment of the message
            var i = 0
            while (i < msg.segments.size) {
                val curr = msg.segments[i]

                // don't split if it's a STRING field
                if (curr.fieldType == NemesysField.STRING) {
                    newSegments.add(curr)
                    i++
                    continue
                }

                val start = curr.offset
                val end = msg.segments.getOrNull(i + 1)?.offset ?: msg.bytes.size
                val segmentBytes = msg.bytes.sliceArray(start until end)

                var splitOffsets = mutableSetOf<Int>()
                splitOffsets.add(0) // segment start

                // compare each segment with every frequent occurring segment
                for (frequent in frequentValues) {
                    if (frequent.size >= segmentBytes.size) continue // skip if not smaller

                    // check if frequent segment is in current segment
                    var searchStart = 0
                    while (searchStart <= segmentBytes.size - frequent.size) { // check if it occurs multiple times
                        val subSegmentBytes = segmentBytes.sliceArray(searchStart until segmentBytes.size)
                        val idx = indexOfSubsequence(subSegmentBytes, frequent) // get index of the subsequenc

                        if (idx != -1) {
                            val absoluteIdx = searchStart + idx
                            splitOffsets.add(absoluteIdx)
                            splitOffsets.add(absoluteIdx + frequent.size)
                            searchStart = absoluteIdx + frequent.size
                        } else {
                            break
                        }
                    }
                }

                splitOffsets.add(segmentBytes.size) // end of the segment

                val sortedOffsets = splitOffsets.toList().sorted().distinct()
                for (j in 0 until sortedOffsets.size - 1) {
                    val relativeOffset = sortedOffsets[j]
                    newSegments.add(NemesysSegment(start + relativeOffset, curr.fieldType))
                }

                i++
            }

            val distinctSorted = newSegments.sortedBy { it.offset }.distinctBy { it.offset }
            msg.copy(segments = distinctSorted)
        }
    }

    // tries to detect length field that determines the rest of the message size in all messages
    fun detectMessageLengthField(messages: List<NemesysParsedMessage>): List<NemesysParsedMessage> {
        // test 1 byte, 2 byte and 4 bytes length field of both endian
        val candidateConfigs = listOf(
            Pair(1, true),
            Pair(2, true),
            Pair(2, false),
            Pair(4, true),
            Pair(4, false)
        )

        // check if any configuration exists
        val validConfigs = candidateConfigs.filter { (size, isBigEndian) ->
            messages.all { msg ->
                detectLengthFieldInMessage(msg, size, isBigEndian) != null
            }
        }
        if (validConfigs.isEmpty()) return messages

        val (chosenSize, chosenEndian) = validConfigs.first() // take first configuration

        // create new segment in all messages
        return messages.map { msg ->
            val match = detectLengthFieldInMessage(msg, chosenSize, chosenEndian)!!
            val (lengthFieldOffset, lengthValue) = match
            val payloadStart = lengthFieldOffset + chosenSize
            // val payloadEnd = payloadStart + lengthValue


            val newSegments = msg.segments.toMutableList()

            // replace old segment if it already exists
            val replacedSegments = newSegments.filterNot { it.offset == lengthFieldOffset }.toMutableList()
            replacedSegments.add(
                NemesysSegment(
                    lengthFieldOffset,
                    if (chosenEndian)
                        NemesysField.PAYLOAD_LENGTH_BIG_ENDIAN
                    else
                        NemesysField.PAYLOAD_LENGTH_LITTLE_ENDIAN
                )
            )

            // add field for payload if no border exits yet
            val payloadOffsetExists = replacedSegments.any { it.offset == payloadStart }
            if (!payloadOffsetExists) {
                replacedSegments.add(NemesysSegment(payloadStart, NemesysField.UNKNOWN))
            }

            val finalSegments = replacedSegments.sortedBy { it.offset }
            msg.copy(segments = finalSegments)

            /*val newSegments = msg.segments.toMutableList()
            newSegments.add(NemesysSegment(lengthFieldOffset,
                if (chosenEndian) NemesysField.PAYLOAD_LENGTH_BIG_ENDIAN else NemesysField.PAYLOAD_LENGTH_LITTLE_ENDIAN))
            newSegments.add(NemesysSegment(payloadStart, NemesysField.UNKNOWN))

            msg.copy(segments = newSegments.sortedBy { it.offset }.distinctBy { it.offset })*/
        }
    }

    // find length field in one given message with specific endian and field size
    fun detectLengthFieldInMessage(
        msg: NemesysParsedMessage,
        lengthFieldSize: Int,
        bigEndian: Boolean
    ): Pair<Int, Int>? {
        val bytes = msg.bytes
        val segments = msg.segments.sortedBy { it.offset }

        for (offset in 0 until bytes.size - lengthFieldSize) {
            val length = NemesysUtil.tryParseLength(bytes, offset, lengthFieldSize, bigEndian) ?: continue
            val payloadStart = offset + lengthFieldSize
            val payloadEnd = payloadStart + length

            // payloadEnd must be message size
            if (payloadEnd != bytes.size) continue

            // length field must be of type UNKNOWN
            val currentSegment = findSegmentForOffset(segments, offset)
            if (currentSegment?.fieldType != NemesysField.UNKNOWN) continue

            return offset to length
        }

        return null
    }

    // get NemesysSegment given an offset
    fun findSegmentForOffset(segments: List<NemesysSegment>, offset: Int): NemesysSegment? {
        for (i in segments.indices) {
            val start = segments[i].offset
            val end = segments.getOrNull(i + 1)?.offset ?: Int.MAX_VALUE
            if (offset in start until end) return segments[i]
        }
        return null
    }

    // refine segments based on all messages
    fun refineSegmentsAcrossMessages(messages: List<NemesysParsedMessage>): List<NemesysParsedMessage> {
        // maybe also implement PCA by Stephan Kleber (written in his dissertation), not sure if it's fast enough
        return messages
            .let(::cropDistinct)
            .let(::detectMessageLengthField)
    }


    // check if segment has subsequence and return the corresponding index
    fun indexOfSubsequence(segment: ByteArray, sub: ByteArray): Int {
        if (sub.isEmpty() || segment.size < sub.size) return -1

        for (i in 0..segment.size - sub.size) {
            if (segment.sliceArray(i until i + sub.size).contentEquals(sub)) return i
        }

        return -1
    }

    // find segmentation boundaries
    private fun findSegmentBoundaries(bytes: ByteArray): List<NemesysSegment> {
        val taken = BooleanArray(bytes.size) { false } // list of bytes that have already been assigned

        // pre processing
        val fixedSegments = detectLengthPrefixedFields(bytes, taken)

        // find all bytes without a corresponding segment
        val freeRanges = mutableListOf<Pair<Int, Int>>()
        var currentStart: Int? = null
        for (i in bytes.indices) {
            if (!taken[i]) {
                if (currentStart == null) currentStart = i
            } else {
                if (currentStart != null) {
                    freeRanges.add(currentStart to i)
                    currentStart = null
                }
            }
        }
        if (currentStart != null) {
            freeRanges.add(currentStart to bytes.size)
        }

        val dynamicSegments = mutableListOf<NemesysSegment>()
        for ((start, end) in freeRanges) {
            val slice = bytes.sliceArray(start until end)
            val deltaBC = computeDeltaBC(slice)

            // sigma should depend on the field length: Nemesys paper on page 5
            val smoothed = applyGaussianFilter(deltaBC, 0.6)

            // Safety check (it mostly enters if the bytes are too short)
            if (smoothed.isEmpty()) {
                dynamicSegments.add(NemesysSegment(start, NemesysField.UNKNOWN))
                continue
            }

            // find extrema of smoothedDeltaBC
            val extrema = findExtremaInList(smoothed)

            // find all rising points from minimum to maximum in extrema list
            val rising = findRisingDeltas(extrema)

            // find inflection point in risingDeltas -> those are considered as boundaries
            val inflection = findInflectionPoints(rising, deltaBC)

            // merge consecutive text segments together
            // val boundaries = mergeCharSequences(preBoundaries, bytes)
            val improved = postProcessing(inflection.toMutableList(), slice)

            // add relativeStart to the boundaries
            for ((relativeStart, type) in improved) {
                dynamicSegments.add(NemesysSegment(start + relativeStart, type))
            }
        }

        // combine segments together
        return (fixedSegments + dynamicSegments).sortedBy { it.offset }.distinctBy { it.offset }
    }


    // check how similar consecutive bytes are
    fun bitCongruence(b1: Byte, b2: Byte): Double {
        var count = 0
        for (i in 0 until 8) {
            if (((b1.toInt() shr i) and 1) == ((b2.toInt() shr i) and 1)) {
                count++
            }
        }
        return count / 8.0
    }

    // get the delta of the bit congruence. Checkout how much consecutive bytes differ
    fun computeDeltaBC(bytes: ByteArray): DoubleArray {
        val n = bytes.size
        if (n < 3) return DoubleArray(0) // return empty array if it's too short

        val bc = DoubleArray(n - 1)
        for (i in 0 until n - 1) {
            bc[i] = bitCongruence(bytes[i], bytes[i + 1])
        }

        val deltaBC = DoubleArray(n - 2)
        for (i in 1 until n - 1) {
            deltaBC[i - 1] = bc[i] - bc[i - 1]
        }

        return deltaBC
    }

    // apply gaussian filter to smooth deltaBC. So we don't interpret every single change as a field boundary
    private fun applyGaussianFilter(deltaBC: DoubleArray, sigma: Double): DoubleArray {
        val radius = ceil(3 * sigma).toInt()
        val size = 2 * radius + 1 // calc kernel size
        val kernel = DoubleArray(size)  // kernel as DoubleArray
        var sum = 0.0

        // calc kernel and sum of values
        for (i in -radius..radius) {
            kernel[i + radius] = exp(-0.5 * (i * i) / (sigma * sigma))  // gaussian weight
            sum += kernel[i + radius]
        }

        // normalize kernel
        for (i in kernel.indices) {
            kernel[i] /= sum
        }

        // calc smoothed array
        val smoothed = DoubleArray(deltaBC.size)
        for (i in deltaBC.indices) {
            smoothed[i] = 0.0
            for (j in -radius..radius) {
                val idx = i + j
                if (idx >= 0 && idx < deltaBC.size) {
                    smoothed[i] += deltaBC[idx] * kernel[j + radius]  // weighted average
                }
            }
        }

        return smoothed
    }

    // get all local minimum and maximum of smoothed deltaBC
    // return List(Index, extrema) with extrema meaning(-1:minimum, 0:nothing, 1:maximum)
    fun findExtremaInList(smoothedDeltaBC: DoubleArray): List<Pair<Int, Int>> {
        val extrema = mutableListOf<Pair<Int, Int>>()

        // get extrema of first point
        if (smoothedDeltaBC.size > 1) {
            if (smoothedDeltaBC[0] < smoothedDeltaBC[1]) {
                extrema.add(0 to -1) // local minimum
            } else if (smoothedDeltaBC[0] > smoothedDeltaBC[1]) {
                extrema.add(0 to 1) // local maximum
            } else {
                extrema.add(0 to 0) // either minimum nor maximum
            }
        }

        // get extrema of middle points
        for (i in 1 until smoothedDeltaBC.size - 1) {
            if (smoothedDeltaBC[i] < smoothedDeltaBC[i - 1] && smoothedDeltaBC[i] < smoothedDeltaBC[i + 1]) {
                extrema.add(i to -1) // local minimum
            } else if (smoothedDeltaBC[i] > smoothedDeltaBC[i - 1] && smoothedDeltaBC[i] > smoothedDeltaBC[i + 1]) {
                extrema.add(i to 1) // local maximum
            } else {
                extrema.add(i to 0) // either minimum nor maximum
            }
        }

        // get extrema of last points
        if (smoothedDeltaBC.size > 1) {
            val lastIndex = smoothedDeltaBC.size - 1
            if (smoothedDeltaBC[lastIndex] < smoothedDeltaBC[lastIndex - 1]) {
                extrema.add(lastIndex to -1) // local minimum
            } else if (smoothedDeltaBC[lastIndex] > smoothedDeltaBC[lastIndex - 1]) {
                extrema.add(lastIndex to 1) // local maximum
            } else {
                extrema.add(lastIndex to 0) // either minimum nor maximum
            }
        }

        return extrema
    }

    // identify rising edges of minimums to maximums
    // extrema must be in format List(Index, min/max) with min/max meaning(-1:minimum, 0:nothing, 1:maximum)
    fun findRisingDeltas(extrema: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val risingDeltas = mutableListOf<Pair<Int, Int>>()
        var lastMinIndex: Int? = null
        for ((index, extremaType) in extrema) {
            if (extremaType == -1) {
                // save index of last mimimun
                lastMinIndex = index
            } else if (extremaType == 1 && lastMinIndex != null) {
                // saved edge if maximum was found and previous minimum exists
                risingDeltas.add(lastMinIndex to index)
                lastMinIndex = null // reset last minimum
            }
        }
        return risingDeltas
    }

    // find inflection point based on maximum delta in rising deltas
    // it adds +2 on the result to because the segment highlighting counts differently
    fun findInflectionPoints(risingDeltas: List<Pair<Int, Int>>, deltaBC: DoubleArray): MutableList<Int> {
        val boundaries = mutableListOf<Int>()

        for ((minIndex, maxIndex) in risingDeltas) {
            var maxDeltaIndex = minIndex + 2
            var maxDeltaValue = 0.0

            for (i in minIndex..< maxIndex) {
                val delta = kotlin.math.abs(deltaBC[i] - deltaBC[i+1])
                if (delta > maxDeltaValue) {
                    maxDeltaValue = delta
                    maxDeltaIndex = i + 2
                }
            }

            boundaries.add(maxDeltaIndex)
        }

        return boundaries
    }

    // check if complete field consists of printable chars
    fun fieldIsTextSegment(start: Int, end: Int, bytes: ByteArray): Boolean {
        for (j in start until end) {
            if (!isPrintableChar(bytes[j])) {
                return false
            }
        }
        return true
    }

    // check if byte is a printable char
    fun isPrintableChar(byte: Byte): Boolean {
        return (byte == 0x09.toByte() || byte == 0x0A.toByte() || byte == 0x0D.toByte() || (byte in 0x20..0x7E))
    }

    // merge consecutive fields together if both are printable char values
    fun mergeCharSequences(boundaries: MutableList<Int>, bytes: ByteArray): MutableList<NemesysSegment> {
        val mergedSegments = mutableListOf<NemesysSegment>()

        // if no boundary detected set start boundary to 0
        if (boundaries.isEmpty()) {
            mergedSegments.add(NemesysSegment(0, NemesysField.UNKNOWN))
            return mergedSegments
        }

        boundaries.add(0, 0)

        var i = 0

        while (i < boundaries.size) {
            // set start and end of segment
            val start = boundaries[i]
            val end = if (i + 1 < boundaries.size) boundaries[i + 1] else bytes.size

            var fieldType = NemesysField.UNKNOWN
            if (fieldIsTextSegment(start, end, bytes)) {
                // merge following segments together if they are also a text segments
                while (i + 1 < boundaries.size) {
                    // set new start and end of segment
                    val nextStart = boundaries[i + 1]
                    val nextEnd = if (i + 2 < boundaries.size) boundaries[i + 2] else bytes.size

                    if (fieldIsTextSegment(nextStart, nextEnd, bytes)) {
                        i++ // skip following segment because we merged it together
                        fieldType = NemesysField.STRING // we have two consecutive text fields interpret it as a string
                    } else {
                        break
                    }
                }
            }

            mergedSegments.add(NemesysSegment(start, fieldType))
            i++
        }

        return mergedSegments
    }

    // try to improve boundaries by shifting them a bit
    private fun postProcessing(boundaries: MutableList<Int>, bytes: ByteArray): List<NemesysSegment> {
        var result = mergeCharSequences(boundaries, bytes)
        result = slideCharWindow(result, bytes)
        result = nullByteTransitions(result, bytes)
        result = entropyMerge(result, bytes)
        return result
    }

    // calc Shannon-Entropy for one segment
    fun calculateShannonEntropy(segment: ByteArray): Double {
        // count the amount of bytes in the segment
        val frequency = mutableMapOf<Byte, Int>()
        for (byte in segment) {
            frequency[byte] = (frequency[byte] ?: 0) + 1
        }

        // calc entropy
        val total = segment.size.toDouble()
        var entropy = 0.0
        for ((_, count) in frequency) {
            val probability = count / total
            entropy -= probability * ln(probability) / ln(2.0)
        }

        return entropy
    }

    // merge two segments based on their entropy
    fun entropyMerge(
        segments: List<NemesysSegment>,
        bytes: ByteArray
    ): MutableList<NemesysSegment> {
        val result = mutableListOf<NemesysSegment>()

        var index = 0
        while (index < segments.size) {
            // get current segment
            val (start, fieldType) = segments[index]
            val end = if (index + 1 < segments.size) segments[index + 1].offset else bytes.size
            val currentSegment = bytes.sliceArray(start until end)
            val currentEntropy = calculateShannonEntropy(currentSegment)

            if (index + 1 < segments.size) { // check if a following segment exists
                val (nextStart, nextFieldType) = segments[index + 1]
                if (fieldType == nextFieldType) {  // check that both field have the same field type
                    val nextEnd = if (index + 2 < segments.size) segments[index + 2].offset else bytes.size
                    val nextSegment = bytes.sliceArray(nextStart until nextEnd)
                    val nextEntropy = calculateShannonEntropy(nextSegment)

                    val entropyDiff = kotlin.math.abs(currentEntropy - nextEntropy)

                    if (currentEntropy > 0.7 && nextEntropy > 0.7 && entropyDiff < 0.05) {
                        // xor of the start bytes for both segments
                        val xorLength = minOf(2, currentSegment.size, nextSegment.size)
                        val xorStart1 = currentSegment.take(xorLength).toByteArray()
                        val xorStart2 = nextSegment.take(xorLength).toByteArray()
                        val xorResult = ByteArray(xorLength) { i -> (xorStart1[i].toInt() xor xorStart2[i].toInt()).toByte() }
                        val xorEntropy = calculateShannonEntropy(xorResult)

                        if (xorEntropy > 0.8) { // in the paper it's set to 0.95 instead of 0.8. Algorithm 3, however, says 0.8
                            // merge segments together
                            result.add(NemesysSegment(start, fieldType))
                            index += 2 // skip the following field because we want to merge it to this one
                            continue
                        }
                    }
                }
            }

            // add regular boundary if we didn't merge any segments
            result.add(NemesysSegment(start, fieldType))
            index++
        }

        return result
    }

    // shift null bytes to the right field
    fun nullByteTransitions(
        segments: MutableList<NemesysSegment>,
        bytes: ByteArray
    ): MutableList<NemesysSegment> {
        for (i in 1 until segments.size) {
            val (prevStart, prevType) = segments[i - 1]
            val (currStart, currType) = segments[i]

            // Rule 1: allocate nullbytes to string field
            if (prevType == NemesysField.STRING) {
                // count null bytes after the segment
                var extra = 0
                while (currStart + extra < bytes.size && bytes[currStart + extra] == 0.toByte()) {
                    extra++
                }
                // only shift boundary if x0 bytes are less than 2 bytes long
                if (extra in 1..2) {
                    segments[i] = NemesysSegment(currStart + extra, currType)
                }
            }

            // Rule 2: nullbytes before UNKNOWN
            if (currType != NemesysField.STRING) {
                // count null bytes in front of the segment
                var count = 0
                var idx = currStart - 1
                while (idx >= prevStart && bytes[idx] == 0.toByte()) {
                    count++
                    idx--
                }

                // only shift boundary if x0 bytes are less than 2 bytes long
                if (count in 1..2) {
                    segments[i] = NemesysSegment(currStart - count, currType)
                }
            }
        }

        return segments.sortedBy { it.offset }.distinctBy { it.offset }.toMutableList()
    }


    // check if left or right byte of char sequence is also part of it
    private fun slideCharWindow(segments: List<NemesysSegment>, bytes: ByteArray): MutableList<NemesysSegment> {
        val improved = mutableListOf<NemesysSegment>()

        var newEnd = 0

        for (i in segments.indices) {
            // get start and end of segment
            var (start, type) = segments[i]
            val end = if (i + 1 < segments.size) segments[i + 1].offset else bytes.size

            // need to check if we already changed the start value in the last round by shifting the boundary
            if (start < newEnd) {
                start = newEnd
            }

            // only shift boundaries if it's a string field
            if (type == NemesysField.STRING) {
                var newStart = start
                newEnd = end

                // check left side
                while (newStart > 0 && isPrintableChar(bytes[newStart - 1])) {
                    newStart--
                }

                // check right side
                while (newEnd < bytes.size && isPrintableChar(bytes[newEnd])) {
                    newEnd++
                }

                improved.add(NemesysSegment(0, NemesysField.STRING))
            } else {
                improved.add(NemesysSegment(start, type))
            }
        }

        return improved
    }

    // check if segment is a char sequence
    private fun isCharSegment(segment: ByteArray): Boolean {
        if (segment.size < 6) return false
        if (!segment.all { it >= 0 && it < 0x7f }) return false

        val nonZeroBytes = segment.filter { it != 0.toByte() }
        if (nonZeroBytes.isEmpty()) return false

        val mean = nonZeroBytes.map { it.toUByte().toInt() }.average()
        if (mean !in 50.0..115.0) return false

        val nonPrintable = nonZeroBytes.count { it < 0x20 || it == 0x7f.toByte() }
        val ratio = nonPrintable.toDouble() / segment.size
        return ratio < 0.33
    }

    // handle length field payload and add it to the result
    fun handlePrintablePayload(
        bytes: ByteArray, taken: BooleanArray, result: MutableList<NemesysSegment>,
        offset: Int, lengthFieldSize: Int, payloadLength: Int, bigEndian: Boolean
    ): Int? {
        // check if payload is printable
        val payloadStart = offset + lengthFieldSize
        val payloadEnd = payloadStart + payloadLength
        val payload = bytes.sliceArray(payloadStart until payloadEnd)
        if (!payload.all { isPrintableChar(it) }) return null

        // check if all following three bytes are also printable
        val lookaheadEnd = minOf(payloadEnd + 3, bytes.size)
        if (payloadEnd < lookaheadEnd) { // skip if there are no follow-up bytes
            val lookahead = bytes.sliceArray(payloadEnd until lookaheadEnd)
            if (lookahead.all { isPrintableChar(it) }) {
                return null
            }
        }

        // finally add fields
        if (bigEndian) {
            result.add(NemesysSegment(offset,NemesysField.PAYLOAD_LENGTH_BIG_ENDIAN))
        } else {
            result.add(NemesysSegment(offset,NemesysField.PAYLOAD_LENGTH_LITTLE_ENDIAN))
        }
        result.add(NemesysSegment(payloadStart,NemesysField.STRING_PAYLOAD))
        for (j in offset until payloadEnd) taken[j] = true

        return payloadEnd
    }

    // used to detect length fields and the corresponding payload
    fun checkLengthPrefixedSegment(
        bytes: ByteArray,
        taken: BooleanArray,
        result: MutableList<NemesysSegment>,
        offset: Int,
        lengthFieldSize: Int,
        bigEndian: Boolean
    ): Int? {
        if (offset + lengthFieldSize >= bytes.size) return null

        val length = NemesysUtil.tryParseLength(bytes, offset, lengthFieldSize, bigEndian) ?: return null

        val payloadStart = offset + lengthFieldSize
        val payloadEnd = payloadStart + length

        // check bounds and collisions
        if (payloadEnd > bytes.size || length < 3) return null
        if ((offset until payloadEnd).any { taken[it] }) return null

        return handlePrintablePayload(bytes, taken, result, offset, lengthFieldSize, length, bigEndian)
    }


    // detect length prefixes and the corresponding payload
    fun detectLengthPrefixedFields(bytes: ByteArray, taken: BooleanArray): List<NemesysSegment> {
        val segments = mutableListOf<NemesysSegment>()
        var i = 0

        while (i < bytes.size - 1) {
            // Try 2-byte length field (big endian)
            var newIndex = checkLengthPrefixedSegment(bytes, taken, segments, i, 2, true)
            if (newIndex != null) {
                i = newIndex
                continue
            }

            // Try 2-byte length field (little endian)
            newIndex = checkLengthPrefixedSegment(bytes, taken, segments, i, 2, false)
            if (newIndex != null) {
                i = newIndex
                continue
            }

            // Try 1-byte length field
            newIndex = checkLengthPrefixedSegment(bytes, taken, segments, i, 1, true)
            if (newIndex != null) {
                i = newIndex
                continue
            }

            i++
        }

        return segments.distinctBy { it.offset }.sortedBy { it.offset }
    }
}

// this object is used as a tool for nemesys parser and renderer
object NemesysUtil {
    // get actual length given the bytes and endian
    fun tryParseLength(
        bytes: ByteArray,
        offset: Int,
        lengthFieldSize: Int,
        bigEndian: Boolean
    ): Int? {
        return try {
            when (lengthFieldSize) {
                1 -> bytes[offset].toUByte().toInt()
                2 -> if (bigEndian)
                    (bytes[offset].toUByte().toInt() shl 8) or bytes[offset + 1].toUByte().toInt()
                else
                    (bytes[offset + 1].toUByte().toInt() shl 8) or bytes[offset].toUByte().toInt()
                4 -> if (bigEndian)
                    (bytes[offset].toUByte().toInt() shl 24) or
                            (bytes[offset + 1].toUByte().toInt() shl 16) or
                            (bytes[offset + 2].toUByte().toInt() shl 8) or
                            (bytes[offset + 3].toUByte().toInt())
                else
                    (bytes[offset + 3].toUByte().toInt() shl 24) or
                            (bytes[offset + 2].toUByte().toInt() shl 16) or
                            (bytes[offset + 1].toUByte().toInt() shl 8) or
                            (bytes[offset].toUByte().toInt())
                else -> null
            }
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }
}
