package decoders

import bitmage.hex
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

class NemesysParser {

    companion object : ByteWitchDecoder {
        override val name = "nemesysparser"

        override fun decode(data: ByteArray, sourceOffset: Int, inlineDisplay: Boolean): ByteWitchResult {
            return NemesysParser().parse(data, sourceOffset, null)
        }

        override fun tryhardDecode(data: ByteArray): ByteWitchResult? {
            return null
        }

        override fun confidence(data: ByteArray) = if(data.size >= 3) 0.76 else 0.00
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
    fun findInflectionPoints(risingDeltas: List<Pair<Int, Int>>, smoothedDeltaBC: DoubleArray): MutableList<Int> {
        val boundaries = mutableListOf<Int>()

        for ((minIndex, maxIndex) in risingDeltas) {
            var maxDeltaIndex = minIndex + 2
            var maxDeltaValue = 0.0

            for (i in minIndex..< maxIndex) {
                val delta = kotlin.math.abs(smoothedDeltaBC[i] - smoothedDeltaBC[i+1])
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
    fun mergeCharSequences(boundaries: MutableList<Int>, bytes: ByteArray): MutableList<Pair<Int, NemesysField>> {
        val mergedBoundaries = mutableListOf<Pair<Int, NemesysField>>()

        // if no boundary detected set start boundary to 0
        if (boundaries.isEmpty()) {
            mergedBoundaries.add(Pair(0, NemesysField.UNKNOWN))
            return mergedBoundaries
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

            mergedBoundaries.add(Pair(start, fieldType))
            i++
        }

        return mergedBoundaries
    }

    // try to improve boundaries by shifting them a bit
    private fun postProcessing(boundaries: MutableList<Int>, bytes: ByteArray): List<Pair<Int, NemesysField>> {
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
        segments: List<Pair<Int, NemesysField>>,
        bytes: ByteArray
    ): MutableList<Pair<Int, NemesysField>> {
        val result = mutableListOf<Pair<Int, NemesysField>>()

        var index = 0
        while (index < segments.size) {
            // get current segment
            val (start, fieldType) = segments[index]
            val end = if (index + 1 < segments.size) segments[index + 1].first else bytes.size
            val currentSegment = bytes.sliceArray(start until end)
            val currentEntropy = calculateShannonEntropy(currentSegment)

            if (index + 1 < segments.size) { // check if a following segment exists
                val (nextStart, nextFieldType) = segments[index + 1]
                if (fieldType == nextFieldType) {  // check that both field have the same field type
                    val nextEnd = if (index + 2 < segments.size) segments[index + 2].first else bytes.size
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
                            result.add(Pair(start, fieldType))
                            index += 2 // skip the following field because we want to merge it to this one
                            continue
                        }
                    }
                }
            }

            // add regular boundary if we didn't merge any segments
            result.add(Pair(start, fieldType))
            index++
        }

        return result
    }

    // shift null bytes to the right field
    fun nullByteTransitions(
        segments: List<Pair<Int, NemesysField>>,
        bytes: ByteArray
    ): MutableList<Pair<Int, NemesysField>> {
        val result = segments.toMutableList()

        for (i in 1 until result.size) {
            val (prevStart, prevType) = result[i - 1]
            val (currStart, currType) = result[i]

            // Rule 1: allocate nullbytes to string field
            if (prevType == NemesysField.STRING) {
                // count null bytes after the segment
                var extra = 0
                while (currStart + extra < bytes.size && bytes[currStart + extra] == 0.toByte()) {
                    extra++
                }
                // only shift boundary if x0 bytes are less than 2 bytes long
                if (extra in 1..2) {
                    result[i] = (currStart + extra) to currType
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
                    result[i] = (currStart - count) to currType
                }
            }

        }

        return result.sortedBy { it.first }.distinctBy { it.first }.toMutableList()
    }


    // check if left or right byte of char sequence is also part of it
    private fun slideCharWindow(segments: List<Pair<Int, NemesysField>>, bytes: ByteArray): MutableList<Pair<Int, NemesysField>> {
        val improved = mutableListOf<Pair<Int, NemesysField>>()

        var newEnd = 0

        for (i in segments.indices) {
            // get start and end of segment
            var (start, type) = segments[i]
            val end = if (i + 1 < segments.size) segments[i + 1].first else bytes.size

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

                improved.add(newStart to NemesysField.STRING)
            } else {
                improved.add(start to type)
            }
        }

        return improved
    }


    // merge char sequences together - this is the way how it's done by Stephan Kleber in his paper
    private fun mergeCharSequences2(boundaries: MutableList<Int>, bytes: ByteArray): List<Pair<Int, NemesysField>> {
        val mergedBoundaries = mutableListOf<Pair<Int, NemesysField>>()

        // if no boundary detected set start boundary to 0
        if (boundaries.isEmpty()) {
            mergedBoundaries.add(0 to NemesysField.UNKNOWN)
            return mergedBoundaries
        }

        boundaries.add(0, 0)
        var i = 0

        while (i < boundaries.size) {
            // set start and end of segment
            val start = boundaries[i]
            var end = if (i + 1 < boundaries.size) boundaries[i + 1] else bytes.size
            var j = i + 1

            while (j + 1 < boundaries.size) {
                val nextStart = boundaries[j]
                val nextEnd = boundaries[j + 1]
                val nextSegment = bytes.sliceArray(nextStart until nextEnd)

                if (nextSegment.all { it >= 0 && it < 0x7f }) {
                    end = nextEnd
                    j++
                } else {
                    break
                }
            }

            val fullSegment = bytes.sliceArray(start until end)

            if (isCharSegment(fullSegment)) {
                mergedBoundaries.add(start to NemesysField.STRING)
                i = j
            } else {
                mergedBoundaries.add(start to NemesysField.UNKNOWN)
                i++
            }
        }

        return mergedBoundaries
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

    // detect length prefixes and the corresponding payload
    fun detectLengthPrefixedFields(bytes: ByteArray, taken: BooleanArray): List<Pair<Int, NemesysField>> {
        val result = mutableListOf<Pair<Int, NemesysField>>()

        // 1 byte sliding window over the message
        var i = 0
        while (i < bytes.size - 1) {
            val payloadLength = bytes[i].toUByte().toInt()
            val payloadStart = i + 1
            val payloadEnd = payloadStart + payloadLength

            // check if payloadEnd exceed the message
            if (payloadEnd > bytes.size) {
                i++
                continue
            }

            val payload = bytes.sliceArray(payloadStart until payloadEnd)
            val entropy = calculateShannonEntropy(payload)
            val isPrintable = payload.all { isPrintableChar(it) }

            // try to check if we have another (length, payload) pair
            val nextValid = if (payloadEnd + 1 < bytes.size) {
                val nextLength = bytes[payloadEnd].toUByte().toInt()
                val nextStart = payloadEnd + 1
                val nextEnd = nextStart + nextLength
                nextEnd <= bytes.size && (nextEnd - nextStart > 0)
            } else false

            // check if payload is a string or has the same structure or if we have a follow-up (length, payload) pair
            if (payloadLength > 3 && (isPrintable /*|| entropy < 3.0 || nextValid*/)) { // TODO better without entropy and nextValid???
                result.add(i to NemesysField.PAYLOAD_LENGTH)
                result.add(payloadStart to if (isPrintable) NemesysField.STRING else NemesysField.UNKNOWN)
                for (j in i until payloadEnd) taken[j] = true
                i = payloadEnd
            } else {
                i++
            }
        }

        return result.distinctBy { it.first }.sortedBy { it.first }
    }



    // find segmentation boundaries
    private fun findSegmentBoundaries(bytes: ByteArray): List<Pair<Int, NemesysField>> {
        val taken = BooleanArray(bytes.size) { false } // list of bytes that have already been assigned

        // post processing
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

        //
        val dynamicSegments = mutableListOf<Pair<Int, NemesysField>>()
        for ((start, end) in freeRanges) {
            val slice = bytes.sliceArray(start until end)
            val deltaBC = computeDeltaBC(slice)

            // sigma should depend on the field length: Nemesys paper on page 5
            val smoothed = applyGaussianFilter(deltaBC, 0.6)

            // Safety check (it mostly enters if the bytes are too short)
            if (smoothed.isEmpty()) continue

            // find extrema of smoothedDeltaBC
            val extrema = findExtremaInList(smoothed)

            // find all rising points from minimum to maximum in extrema list
            val rising = findRisingDeltas(extrema)

            // find inflection point in risingDeltas -> those are considered as boundaries
            val inflection = findInflectionPoints(rising, smoothed)

            // merge consecutive text segments together
            // val boundaries = mergeCharSequences(preBoundaries, bytes)
            val improved = postProcessing(inflection.toMutableList(), slice)

            // add relativeStart to the boundaries
            for ((relativeStart, type) in improved) {
                dynamicSegments.add((start + relativeStart) to type)
            }
        }

        // combine segments together
        return (fixedSegments + dynamicSegments).sortedBy { it.first }.distinctBy { it.first }



        /*

        val deltaBC = computeDeltaBC(bytes)

        // sigma should depend on the field length: Nemesys paper on page 5
        val smoothedDeltaBC = applyGaussianFilter(deltaBC, 0.6)

        // Safety check (it mostly enters if the bytes are too short)
        if (smoothedDeltaBC.isEmpty()) return listOf<Pair<Int, NemesysField>>()

        // find extrema of smoothedDeltaBC
        val extrema = findExtremaInList(smoothedDeltaBC)

        // find all rising points from minimum to maximum in extrema list
        val risingDeltas = findRisingDeltas(extrema)

        // find inflection point in risingDeltas -> those are considered as boundaries
        val preBoundaries = findInflectionPoints(risingDeltas, smoothedDeltaBC)

        // merge consecutive text segments together
        // val boundaries = mergeCharSequences(preBoundaries, bytes)
        val boundaries = improveBoundaries(preBoundaries, bytes)

        return boundaries

         */
    }

    // this finds all segment boundaries and returns a nemesys object that can be called to get the html code
    fun parse(bytes: ByteArray, sourceOffset: Int, msgIndex: Int?): NemesysObject {
        val segments = findSegmentBoundaries(bytes)

        return NemesysObject(segments, bytes, Pair(sourceOffset, sourceOffset+bytes.size), msgIndex)
    }
}

enum class NemesysField {
    UNKNOWN, STRING, PAYLOAD_LENGTH
}

// this object can be used to get useable html code from the nemesys parser
class NemesysObject(
    val segments: List<Pair<Int, NemesysField>>,
    val bytes: ByteArray,
    override val sourceByteRange: Pair<Int, Int>?,
    val msgIndex: Int?) : ByteWitchResult {

    // html view of the normal (non-editable) byte sequences
    fun renderPrettyHTML(): String {
        // val updatedSegments = updateFieldType(segments, bytes) // TODO don't think this is needed because we check it later anyway by using NemesysField.UNKOWN
        val updatedSegments = segments

        val sourceOffset = sourceByteRange?.first ?: 0

        val renderedFieldContents = updatedSegments.mapIndexed { index, (start, fieldType) ->
            val end = if (index + 1 < updatedSegments.size) updatedSegments[index + 1].first else bytes.size
            val segmentBytes = bytes.sliceArray(start until end)

            val valueLengthTag = " data-start='${start+sourceOffset}' data-end='${end+sourceOffset}'"
            val valueAlignId = " value-align-id='$msgIndex-$index'"

            // differentiate between field types
            when (fieldType) {
                // TODO maybe we shouldn't work with NemesysField.STRING because it is automatically detected as a string anyway by the quickDecoder
                NemesysField.STRING ->
                    "<div class=\"nemesysfield roundbox data\" $valueLengthTag $valueAlignId>" +
                        "<div class=\"nemesysvalue\" $valueLengthTag>" +
                            "${segmentBytes.hex()} <span>→</span> \"${segmentBytes.decodeToString()}\"" +
                        "</div>" +
                    "</div>"
                NemesysField.PAYLOAD_LENGTH -> {
                    val decode = ByteWitch.quickDecode(segmentBytes, start + sourceOffset)

                    // check if we have to wrap content
                    val requiresWrapping =
                        decode == null || decode is NemesysObject || decode is BWString || decode is BWAnnotatedData
                    val prePayload =
                        if (requiresWrapping) "<div style='color:blue' class=\"nemesysfield roundbox data\" $valueLengthTag $valueAlignId>" else "<div $valueAlignId>"
                    val postPayload = if (requiresWrapping) "</div>" else "</div>"

                    // if it's a nemesys object again, just show the hex output
                    if (decode == null || decode is NemesysObject) { // settings changed so it can't be a NemesysObject anymore but it can be null
                        "$prePayload<div style='color:blue' class=\"nemesysvalue\" $valueLengthTag>${segmentBytes.hex()}</div>$postPayload"
                    } else {
                        "$prePayload${decode.renderHTML()}$postPayload"
                    }
                }
                else -> {
                    val decode = ByteWitch.quickDecode(segmentBytes, start+sourceOffset)

                    // check if we have to wrap content
                    val requiresWrapping = decode == null || decode is NemesysObject || decode is BWString || decode is BWAnnotatedData
                    val prePayload = if(requiresWrapping) "<div class=\"nemesysfield roundbox data\" $valueLengthTag $valueAlignId>" else "<div $valueAlignId>"
                    val postPayload = if(requiresWrapping) "</div>" else "</div>"

                    // if it's a nemesys object again, just show the hex output
                    if (decode == null || decode is NemesysObject) { // settings changed so it can't be a NemesysObject anymore but it can be null
                        "$prePayload<div class=\"nemesysvalue\" $valueLengthTag>${segmentBytes.hex()}</div>$postPayload"
                    } else {
                        "$prePayload${decode.renderHTML()}$postPayload"
                    }
                }
            }
        }

        val content = "<div class=\"nemesysfield roundbox\"><div>${renderedFieldContents.joinToString("")}</div></div>"

        val editButton = "<div class=\"icon icon-edit edit-button\"></div>"

        val renderedContent = "<div class=\"nemesys roundbox\" $byteRangeDataTags>" +
                "<div class=\"view-default\">$editButton$content</div>" +
                "<div class=\"view-editable\" style=\"display:none;\">${renderEditableHTML()}</div>" +
                "</div>"

        return renderedContent
    }

    // html view of editable byte sequences
    private fun renderEditableHTML(): String {
        val renderedFieldContents = mutableListOf<String>()

        for ((index, segment) in segments.withIndex()) {
            val (start, fieldType) = segment
            val end = if (index + 1 < segments.size) segments[index + 1].first else bytes.size
            val segmentBytes = bytes.sliceArray(start until end)

            // create byte-groups of two bytes
            val groupedHex = segmentBytes.hex().chunked(2).joinToString("<div class=\"separator-placeholder\"></div>") {
                "<div class='bytegroup'>$it</div>"
            }

            if (end != bytes.size) {
                renderedFieldContents.add("$groupedHex<div class='field-separator'>|</div>")
            } else {
                renderedFieldContents.add(groupedHex)
            }
        }

        val finishButton = "<div class=\"icon icon-finish finish-button\"></div>"

        val renderedContent = "<div class='nemesysfield roundbox'><div>" +
                "<div class='nemesysvalue' id=\"byteContainer\">" +
                "${renderedFieldContents.joinToString("")}</div></div></div>"

        return "$finishButton$renderedContent"
    }

    override fun renderHTML(): String {
        return renderPrettyHTML()
    }

    // update field type. e.g. check if a byte sequence is utf8 decoded
    private fun updateFieldType(segments: List<Pair<Int, NemesysField>>, bytes: ByteArray): List<Pair<Int, NemesysField>> {
        return segments.mapIndexed { index, (start, fieldType) ->
            if (fieldType == NemesysField.UNKNOWN) {
                // get segmented byte sequence by using the start index and calc the end index with the following start index
                val end = if (index + 1 < segments.size) segments[index + 1].first else bytes.size
                val segmentBytes = bytes.sliceArray(start until end)

                start to checkFieldType(segmentBytes)
            } else {
                start to fieldType
            }
        }
    }

    // get the field type of a byte sequence
    private fun checkFieldType(bytes: ByteArray) : NemesysField{
        return NemesysField.UNKNOWN
    }

    // returns segments of the nemesys object
    fun getSegments(): List<Pair<Int, NemesysField>> {
        return segments
    }
}

// class for sequence alignment of nemesys object
object NemesysSequenceAlignment {

    // main function for sequence alignment
    fun alignSegments(
        messages: Map<Int, ByteArray>,
        nemesysSegments: Map<Int, List<Pair<Int, NemesysField>>>
    ): List<AlignedSegment> {
        val alignments = mutableListOf<AlignedSegment>()
        val tresholdAlignedSegment = 0.17

        // get dissimilarity matrix (by using canberra-ulm dissimilarity)
        val sparseMatrixData = calcSparseSimilarityMatrix(messages, nemesysSegments, tresholdAlignedSegment)

        Logger.log("Matrix")
        Logger.log(sparseMatrixData)

        for ((pair, matrixS) in sparseMatrixData) {
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
        messages: Map<Int, ByteArray>,
        nemesysSegments: Map<Int, List<Pair<Int, NemesysField>>>,
        similarityThreshold: Double
    ): Map<Pair<Int, Int>, Map<Pair<Int, Int>, Double>> {
        val result = mutableMapOf<Pair<Int, Int>, MutableMap<Pair<Int, Int>, Double>>()

        // go through all messages
        for (i in messages.keys) {
            for (j in messages.keys) {
                if (i >= j) continue // do not compare messages twice

                // extract segments and bytes from list
                val segmentsA = nemesysSegments[i] ?: continue
                val segmentsB = nemesysSegments[j] ?: continue
                val bytesA = messages[i] ?: continue
                val bytesB = messages[j] ?: continue

                val simMap = mutableMapOf<Pair<Int, Int>, Double>()

                // compare all segments form A with all segments of B
                for (segmentAIndex in segmentsA.indices) {
                    // extract bytes from segmentA
                    val (startA, _) = segmentsA[segmentAIndex]
                    val endA = if (segmentAIndex + 1 < segmentsA.size) segmentsA[segmentAIndex + 1].first else bytesA.size
                    val segmentBytesA = bytesA.sliceArray(startA until endA)

                    for (segmentBIndex in segmentsB.indices) {
                        // extract bytes from segmentB
                        val (startB, _) = segmentsB[segmentBIndex]
                        val endB = if (segmentBIndex + 1 < segmentsB.size) segmentsB[segmentBIndex + 1].first else bytesB.size
                        val segmentBytesB = bytesB.sliceArray(startB until endB)

                        val dissim = canberraUlmDissimilarity(segmentBytesA, segmentBytesB/*, startA, startB*/)
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
    fun canberraUlmDissimilarity(segmentS: ByteArray, segmentT: ByteArray): Double {
        val shortSegment = if (segmentS.size <= segmentT.size) segmentS else segmentT
        val longSegment = if (segmentS.size > segmentT.size) segmentS else segmentT

        var minD = Double.MAX_VALUE

        // sliding window to search for the lowest dissimilarity
        for (offset in 0..(longSegment.size - shortSegment.size)) {
            val window = longSegment.sliceArray(offset until (offset + shortSegment.size))
            val dC = canberraDistance(shortSegment, window) / shortSegment.size
            if (dC < minD) {
                minD = dC
            }
        }

        val r = (longSegment.size - shortSegment.size).toDouble() / longSegment.size
        val pf = 1.0 // hyper parameter to set the non-linear penalty

        val dm = (shortSegment.size.toDouble() / longSegment.size.toDouble()) * minD +
                r +
                (1 - minD) * r * (shortSegment.size / (longSegment.size * longSegment.size) - pf)

        return dm
    }

    // Canberra Dissimilarity for segments of different sizes (using pooling)
    private fun canberraDissimilarityWithPooling(segmentA: ByteArray, segmentB: ByteArray): Double {
        val shortSegment: ByteArray
        val longSegment: ByteArray

        // determine longer and shorter segment
        if (segmentA.size <= segmentB.size) {
            shortSegment = segmentA
            longSegment = segmentB
        } else {
            shortSegment = segmentB
            longSegment = segmentA
        }

        // pool longer segment
        val pooledSegment = averagePoolSegment(longSegment, shortSegment.size)

        // now just use the regular canberraDistance with pooledSegment
        return canberraDistance(shortSegment, pooledSegment) / shortSegment.size
    }

    // average pooling of a segment to transform it in a lower dimension
    private fun averagePoolSegment(segment: ByteArray, targetSize: Int): ByteArray {
        val pooled = ByteArray(targetSize)
        val chunkSize = segment.size.toDouble() / targetSize

        for (i in 0 until targetSize) {
            // chunk that we need to pool
            val start = (i * chunkSize).toInt()
            val end = ((i + 1) * chunkSize).toInt().coerceAtMost(segment.size)
            val chunk = segment.sliceArray(start until end)

            val avgValue = if (chunk.isNotEmpty()) {
                chunk.map { it.toInt() and 0xFF }.average().toInt() // calc average value
            } else {
                0
            }

            pooled[i] = avgValue.toByte()
        }

        return pooled
    }


    // position penalty for absolute and relative difference
    private fun computePositionPenalty(startA: Int, startB: Int, lenA: Int, lenB: Int): Double {
        val alpha = 0.02 // hyper parameter for absolute difference
        val beta = 0.03  // hyper parameter for relative difference

        // calc penalty for absolute difference
        val maxLen = maxOf(lenA, lenB).toDouble()
        val positionDiffAbs = kotlin.math.abs(startA - startB).toDouble()
        val penaltyAbs = positionDiffAbs / maxLen

        // calc penalty for relative difference
        val relativePosA = startA.toDouble() / lenA
        val relativePosB = startB.toDouble() / lenB
        val penaltyRel = kotlin.math.abs(relativePosA - relativePosB)

        return alpha * penaltyAbs + beta * penaltyRel
    }

}

// aligned segment for Nemesys
data class AlignedSegment(
    val protocolA: Int,
    val protocolB: Int,
    val segmentIndexA: Int,
    val segmentIndexB: Int,
    val dissimilarity: Double
)
