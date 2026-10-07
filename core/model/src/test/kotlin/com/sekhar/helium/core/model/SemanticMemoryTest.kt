package com.sekhar.helium.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticMemoryTest {

    private fun memory(): SemanticVideoMemory {
        val metadata = VideoMetadata(durationMs = 20_000L, width = 1080, height = 1920, hasAudio = true)
        return SemanticVideoMemory(
            sourceId = SourceId("src-1"),
            fingerprint = "fp",
            metadata = metadata,
            stages = setOf(AnalysisStage.TRANSCRIPT, AnalysisStage.EVENTS),
            transcript = listOf(
                TranscriptSegment(TimeRange(0L, 2_000L), "okay so here we go"),
                TranscriptSegment(TimeRange(2_000L, 5_000L), "and then the phone falls"),
                TranscriptSegment(TimeRange(9_000L, 11_000L), "oh no"),
            ),
            ocr = listOf(
                OcrText("ocr-1", TimeRange(1_000L, 3_000L), "CALL 9876543210", 0.9f),
            ),
            audioEvents = listOf(
                AudioEvent(EventId("a1"), AudioEventType.IMPACT, 4_900L, level = 0.9f),
                AudioEvent(EventId("a2"), AudioEventType.SILENCE, 6_000L, durationMs = 2_000L),
            ),
            eventPackets = listOf(
                EventPacket(
                    id = EventId("e1"),
                    startMs = 4_400L,
                    apexMs = 4_900L,
                    endMs = 5_400L,
                    before = "person holding phone",
                    action = "phone rapidly moving downward",
                    after = "phone on floor",
                    score = 0.95f,
                ),
            ),
            informationCurve = listOf(
                InformationSample(0L, 0.1f),
                InformationSample(1_000L, 0.7f),
                InformationSample(4_900L, 1.0f),
            ),
            motionSegments = listOf(
                MotionSegment(TimeRange(4_000L, 6_000L), MotionLevel.HIGH, 0.8f, 0.97f),
                MotionSegment(TimeRange(0L, 1_000L), MotionLevel.STATIC, 0.01f, 0.02f),
            ),
            silenceRegions = listOf(TimeRange(6_000L, 8_000L)),
            speechRegions = listOf(TimeRange(0L, 5_000L), TimeRange(9_000L, 11_000L)),
        )
    }

    @Test
    fun findsSpokenTextCaseInsensitively() {
        val matches = memory().findSpokenText("PHONE")
        assertEquals(1, matches.size)
        assertEquals(TextMatchSource.SPEECH, matches.first().source)
        assertEquals(2_000L, matches.first().range.startMs)
    }

    @Test
    fun findsOnScreenText() {
        val matches = memory().findVisibleText("9876543210")
        assertEquals(1, matches.size)
        assertEquals(TextMatchSource.ON_SCREEN, matches.first().source)
    }

    @Test
    fun emptyQueryReturnsNothing() {
        assertTrue(memory().findSpokenText("   ").isEmpty())
    }

    @Test
    fun informationScoreInterpolatesBetweenSamples() {
        val memory = memory()
        assertEquals(0.1f, memory.informationScoreAt(0L), 0.001f)
        assertEquals(1.0f, memory.informationScoreAt(4_900L), 0.001f)
        // Halfway between 0.1 and 0.7 at 500ms.
        assertEquals(0.4f, memory.informationScoreAt(500L), 0.001f)
        // Beyond the curve the last value holds.
        assertEquals(1.0f, memory.informationScoreAt(99_000L), 0.001f)
    }

    @Test
    fun silenceFilteringRespectsMinimumDuration() {
        val memory = memory()
        assertEquals(1, memory.silences(minDurationMs = 1_500L).size)
        assertTrue(memory.silences(minDurationMs = 5_000L).isEmpty())
    }

    @Test
    fun mostInterestingMomentsRanksTheImpact() {
        val moments = memory().mostInterestingMoments(limit = 3)
        assertTrue(moments.isNotEmpty())
        // The impact window must be centred near the apex at 4.9s.
        assertTrue(
            moments.any { it.startMs in 3_500L..5_500L },
            "expected a candidate around the impact, got $moments",
        )
    }

    @Test
    fun transcriptHelpersFilterByRange() {
        val memory = memory()
        assertEquals(2, memory.transcriptInRange(1_000L, 6_000L).size)
        assertTrue(memory.transcriptTextIn(TimeRange(0L, 2_000L)).contains("here we go"))
    }

    @Test
    fun overviewCarriesScenesAndEvents() {
        val overview = memory().toOverview()
        assertEquals(SourceId("src-1"), overview.sourceId)
        assertEquals(20_000L, overview.durationMs)
        assertEquals(Orientation.PORTRAIT, overview.orientation)
        assertEquals(1, overview.majorEvents.size)
    }
}
