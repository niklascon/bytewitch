package ParserTests

import decoders.SwiftSegFinder.SSFParsedMessage
import decoders.SwiftSegFinder.SSFParser
import kotlin.test.Test

class TestCustomParser {
    // choose the parser
    private fun parserForSegmentParsing(bytes: ByteArray, index: Int): SSFParsedMessage {
        return SSFParser().parse(bytes, index)
    }

    /*@Test
    fun testSegmentation() {
        TestMessageSamples.testMessages.forEachIndexed { index, testMessage ->
            val parsed = parserForSegmentParsing(testMessage.message, index)
            EvaluationHelper.printSegmentParsingResult(index, testMessage.segments, parsed.segments)
        }

        EvaluationHelper.printFinalScore()
    }*/

    @Test
    fun testSegmentation() {
        var totalTimeMs = 0.0

        TestMessageSamples.testMessages.forEachIndexed { index, testMessage ->
            val start = kotlin.js.Date().getTime()
            val parsed = parserForSegmentParsing(testMessage.message, index)
            val end = kotlin.js.Date().getTime()
            val durationMs = end - start
            totalTimeMs += durationMs

            EvaluationHelper.printSegmentParsingResult(index, testMessage.segments, parsed.segments)
        }

        val avgTime = if (TestMessageSamples.testMessages.isNotEmpty())
            totalTimeMs / TestMessageSamples.testMessages.size
        else 0.0

        println("Total runtime: ${totalTimeMs}ms")

        EvaluationHelper.printFinalScore()
    }



    /*
    @Test
    fun testMessageGroupSegmentationWithEntropy() {
        TestMessageSamples.messageGroups.forEach { group ->
            println("=== Testing Custom Parser Group ${group.typeId} with Entropy ===")

            val emptyParsedMessages = group.messages.map { testMessage ->
                SSFParsedMessage(emptyList(), testMessage.message, testMessage.index)
            }

            val entropyParsedMessages = SSFParser().parseEntropy(emptyParsedMessages)

            group.messages.zip(entropyParsedMessages).forEach { (testMessage, parsed) ->
                EvaluationHelper.printSegmentParsingResult(
                    testMessage.index,
                    testMessage.segments,
                    parsed.segments
                )
            }

            EvaluationHelper.printFinalScore()
        }

        assertTrue(false, "F1 score should be at least 80%")
    }
    */

    @Test
    fun testMessageGroupSegmentation() {
        TrainingMessageSamples.messageGroups.forEach { group ->
            println("=== Testing Custom Parser Group ${group.typeId} ===")
            group.messages.forEach { testMessage ->
                val parsed = parserForSegmentParsing(testMessage.message, testMessage.index)
                EvaluationHelper.printSegmentParsingResult(testMessage.index, testMessage.segments, parsed.segments)
            }
        }

        EvaluationHelper.printFinalScore()
    }

    @Test
    fun testSegmentWiseSequenceAlignment() {
        for ((index, test) in TrainingMessageSamples.alignmentTests.withIndex()) {
            val msgA = TrainingMessageSamples.testMessages[test.messageAIndex]
            val msgB = TrainingMessageSamples.testMessages[test.messageBIndex]
            val messages = mapOf(
                test.messageAIndex to SSFParsedMessage(msgA.segments, msgA.message, test.messageAIndex),
                test.messageBIndex to SSFParsedMessage(msgB.segments, msgB.message, test.messageBIndex)
            )
            EvaluationHelper.printByteWiseSequenceAlignmentResult(index, messages, test.expectedAlignments)
        }

        EvaluationHelper.printFinalScore()
    }

    @Test
    fun testByteWiseSequenceAlignment() {
        for ((index, test) in TrainingMessageSamples.alignmentTests.withIndex()) {
            val msgA = TrainingMessageSamples.testMessages[test.messageAIndex]
            val msgB = TrainingMessageSamples.testMessages[test.messageBIndex]
            val messages = mapOf(
                test.messageAIndex to SSFParsedMessage(msgA.segments, msgA.message, test.messageAIndex),
                test.messageBIndex to SSFParsedMessage(msgB.segments, msgB.message, test.messageBIndex)
            )
            EvaluationHelper.printSequenceAlignmentResult(index, messages, test.expectedAlignments)
        }

        EvaluationHelper.printFinalScore()
    }

    @Test
    fun testSegmentationWithSequenceAlignment() {
         for ((index, test) in TrainingMessageSamples.alignmentTests.withIndex()) {
            val msgA = TrainingMessageSamples.testMessages[test.messageAIndex]
            val msgB = TrainingMessageSamples.testMessages[test.messageBIndex]

            // do segmentation
            val parsedA = parserForSegmentParsing(msgA.message, test.messageAIndex)
            val parsedB = parserForSegmentParsing(msgB.message, test.messageBIndex)

            val actualParsed = mapOf(
                test.messageAIndex to parsedA,
                test.messageBIndex to parsedB
            )

            val expectedParsed = mapOf(
                test.messageAIndex to SSFParsedMessage(msgA.segments, msgA.message, test.messageAIndex),
                test.messageBIndex to SSFParsedMessage(msgB.segments, msgB.message, test.messageBIndex)
            )

            // do segmentation
            EvaluationHelper.printSegmentationWithSequenceAlignmentResult(
                testNumber = index,
                actualMessages = actualParsed,
                expectedSegments = expectedParsed,
                expectedAlignments = test.expectedAlignments
            )
        }

        EvaluationHelper.printFinalScore()
    }

}