package ParserTests

import decoders.SwiftSegFinder.SSFParsedMessage
import decoders.SwiftSegFinder.SSFParser
import kotlin.test.Test
import kotlin.test.assertTrue

class TestCustomParser {
    // choose the parser
    private fun parserForSegmentParsing(bytes: ByteArray, index: Int): SSFParsedMessage {
        return SSFParser().parse(bytes, index)
    }

    @Test
    fun testSegmentation() {
        var totalTimeMs = 0.0

        TestMessageSamples.testMessages.forEachIndexed { index, testMessage ->
            if (index in 0.. 18) {
                val start = kotlin.js.Date().getTime()
                val parsed = parserForSegmentParsing(testMessage.message, index)
                val end = kotlin.js.Date().getTime()
                val durationMs = end - start
                totalTimeMs += durationMs

                EvaluationHelper.printSegmentParsingResult(index, testMessage.segments, parsed.segments)
            }
        }

        println("Total runtime: ${totalTimeMs}ms")

        EvaluationHelper.printFinalScore()
    }

    /*@Test
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

    @Test
    fun testMessageGroupSegmentationWithEntropyWithRefinement() {
        TestMessageSamples.messageGroups.forEach { group ->
            println("=== Testing Custom Parser Group ${group.typeId} with Entropy and Across Message Refinment ===")

            val emptyParsedMessages = group.messages.map { testMessage ->
                SSFParsedMessage(emptyList(), testMessage.message, testMessage.index)
            }

            val entropyParsedMessages = SSFParser().parseEntropy(emptyParsedMessages)

            val refinedMessages = SSFParser().refineSegmentsAcrossMessages(entropyParsedMessages)

            group.messages.zip(refinedMessages).forEach { (testMessage, refined) ->
                EvaluationHelper.printSegmentParsingResult(
                    testMessage.index,
                    testMessage.segments,
                    refined.segments
                )
            }

            EvaluationHelper.printFinalScore()
        }

        assertTrue(false, "F1 score should be at least 80%")
    }


    @Test
    fun testMessageGroupSegmentation() {
        TestMessageSamples.messageGroups.forEach { group ->
            var totalTimeMs = 0.0

            println("=== Testing Custom Parser Group ${group.typeId} ===")
            group.messages.forEach { testMessage ->
                val start = kotlin.js.Date().getTime()
                val parsed = parserForSegmentParsing(testMessage.message, testMessage.index)
                val end = kotlin.js.Date().getTime()
                val durationMs = end - start
                totalTimeMs += durationMs

                EvaluationHelper.printSegmentParsingResult(testMessage.index, testMessage.segments, parsed.segments)
            }

            println("Total runtime: ${totalTimeMs}ms")

            EvaluationHelper.printFinalScore() // TODO wenn das benutzt wird, muss in printFinalScore das assertTrue raus!!!
        }

        assertTrue(false, "F1 score should be at least 80%")
    }*/

    /*@Test
    fun testMessageGroupSegmentationWithRefinement() {
        fun nowMs(): Double = js("performance.now()") as Double

        TestMessageSamples.messageGroups.forEach { group ->
            println("=== Testing Custom Parser Group ${group.typeId} with Across Message Refinement ===")

            // parse all messages
            val tParseStart = nowMs()
            val parsedByIndex: MutableMap<Int, SSFParsedMessage> = mutableMapOf()

            group.messages.forEach { testMessage ->
                val parsed = parserForSegmentParsing(testMessage.message, testMessage.index)
                parsedByIndex[testMessage.index] = parsed
            }
            val tParseEnd = nowMs()

            // refinement for all parsed messages
            val parsedListInOrder: List<SSFParsedMessage> = group.messages.map { parsedByIndex.getValue(it.index) } // set order
            val tRefineStart = nowMs()
            val refinedList: List<SSFParsedMessage> = SSFParser().refineSegmentsAcrossMessages(parsedListInOrder)
            val tRefineEnd = nowMs()

            // evaluation per message
            refinedList.forEachIndexed { i, refinedMsg ->
                val testMessage = group.messages[i]
                EvaluationHelper.printSegmentParsingResult(
                    testMessage.index,
                    testMessage.segments, // ground truth
                    refinedMsg.segments // predicted
                )
            }

            println("Parse runtime: ${(tParseEnd - tParseStart)}ms")
            println("Refine runtime: ${(tRefineEnd - tRefineStart)}ms")
            println("Total runtime: ${((tParseEnd - tParseStart) + (tRefineEnd - tRefineStart))}ms")

            EvaluationHelper.printFinalScore() // TODO wenn das benutzt wird, muss in printFinalScore das assertTrue raus!!!
        }

        assertTrue(false, "F1 score should be at least 80%")
    }*/


    /*@Test
    fun testSegmentWiseSequenceAlignment() {
        for ((index, test) in TestMessageSamples.alignmentTests.withIndex()) {
            val msgA = TestMessageSamples.testMessages[test.messageAIndex]
            val msgB = TestMessageSamples.testMessages[test.messageBIndex]
            val messages = mapOf(
                test.messageAIndex to SSFParsedMessage(msgA.segments, msgA.message, test.messageAIndex),
                test.messageBIndex to SSFParsedMessage(msgB.segments, msgB.message, test.messageBIndex)
            )
            EvaluationHelper.printSegmentWiseSequenceAlignmentResult(index, messages, test.expectedAlignments)
        }

        // EvaluationHelper.printFinalScore()
        EvaluationHelper.printFinalScoreSequenceAlignment()
    }

    @Test
    fun testByteWiseSequenceAlignment() {
        for ((index, test) in TestMessageSamples.alignmentTests.withIndex()) {
            val msgA = TestMessageSamples.testMessages[test.messageAIndex]
            val msgB = TestMessageSamples.testMessages[test.messageBIndex]
            val messages = mapOf(
                test.messageAIndex to SSFParsedMessage(msgA.segments, msgA.message, test.messageAIndex),
                test.messageBIndex to SSFParsedMessage(msgB.segments, msgB.message, test.messageBIndex)
            )
            EvaluationHelper.printByteWiseSequenceAlignmentResult(index, messages, test.expectedAlignments)
        }

        // EvaluationHelper.printFinalScore()
        EvaluationHelper.printFinalScoreSequenceAlignment()
    }*/

    // Das macht irgendwie ein Tick zu wenig Sinn und ist nicht wirklich vergleichbar
    /*@Test
    fun testSegmentationWithSequenceAlignment() {
         for ((index, test) in TestMessageSamples.alignmentTests.withIndex()) {
            val msgA = TestMessageSamples.testMessages[test.messageAIndex]
            val msgB = TestMessageSamples.testMessages[test.messageBIndex]

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
    }*/

}