package com.quietnight.app

import com.quietnight.app.audio.AudioSegment
import com.quietnight.app.audio.SegmentIndex
import com.quietnight.app.data.NightSeek
import com.quietnight.app.data.SeekPos
import com.quietnight.app.data.Session
import com.quietnight.app.data.SessionRepository
import com.quietnight.app.data.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class NightSeekTest {
    @get:Rule val tmp = TemporaryFolder()

    // 0-10 min, gap, 15-25 min, 25-30 min (back to back)
    private val segs = NightSeek.ordered(listOf(
        AudioSegment("audio-003.m4a", 25 * 60_000L, 5 * 60_000L),
        AudioSegment("audio-001.m4a", 0, 10 * 60_000L),
        AudioSegment("audio-002.m4a", 15 * 60_000L, 10 * 60_000L),
        AudioSegment("empty.m4a", 40 * 60_000L, 0),
    ))

    @Test fun orderedDropsEmptyAndSorts() {
        assertEquals(listOf("audio-001.m4a", "audio-002.m4a", "audio-003.m4a"), segs.map { it.file })
        assertEquals(25 * 60_000L, NightSeek.totalMs(segs))
    }

    @Test fun locateInsideGapAndEnds() {
        assertEquals(SeekPos(0, 0), NightSeek.locate(segs, 0))
        assertEquals(SeekPos(0, 90_000), NightSeek.locate(segs, 90_000))
        assertEquals(SeekPos(1, 0), NightSeek.locate(segs, 12 * 60_000L)) // in the gap: skip to the next segment
        assertEquals(SeekPos(1, 60_000), NightSeek.locate(segs, 16 * 60_000L))
        assertEquals(SeekPos(2, 0), NightSeek.locate(segs, 25 * 60_000L)) // boundary belongs to the next
        assertNull(NightSeek.locate(segs, 30 * 60_000L)) // after the end
        assertNull(NightSeek.locate(emptyList(), 0))
        assertEquals(SeekPos(0, 0), NightSeek.locate(segs, -5_000)) // before the first
    }

    @Test fun positionsRoundTripAndNext() {
        for (t in listOf(0L, 61_000L, 15 * 60_000L + 1, 29 * 60_000L)) {
            assertEquals(t, NightSeek.nightMs(segs, NightSeek.locate(segs, t)!!))
        }
        assertEquals(1, NightSeek.next(segs, 0))
        assertEquals(2, NightSeek.next(segs, 1))
        assertNull(NightSeek.next(segs, 2))
        assertTrue(NightSeek.covered(segs, 5 * 60_000L))
        assertFalse(NightSeek.covered(segs, 12 * 60_000L))
    }

    @Test fun clipsAndNightAudioAreDeletedSeparately() {
        val repo = SessionRepository(tmp.root)
        val s = Session("n", 1_000_000L, 1_060_000L, 10, ByteArray(6), ByteArray(6), emptyList(), listOf(Clip(0, "c0.wav", 2, 20)))
        repo.save(s)
        val dir = repo.clipDir("n").apply { mkdirs() }
        File(dir, "c0.wav").writeBytes(ByteArray(100))
        File(dir, "audio-001.m4a").writeBytes(ByteArray(1000))
        File(dir, "audio-002.m4a").writeBytes(ByteArray(500))
        SegmentIndex.write(dir, listOf(AudioSegment("audio-001.m4a", 0, 1000), AudioSegment("audio-002.m4a", 2000, 1000)))
        val indexBytes = File(dir, SegmentIndex.FILE).length()

        assertEquals(100L, repo.clipBytes("n"))
        assertEquals(1500L + indexBytes, repo.audioBytes("n"))

        assertEquals(100L, repo.deleteClips("n"))
        assertTrue(repo.sessions.value.single().clips.isEmpty())
        assertEquals(2, SegmentIndex.read(dir).size) // the night's audio is untouched
        assertEquals(1500L + indexBytes, repo.audioBytes("n"))

        assertEquals(1500L + indexBytes, repo.deleteNightAudio("n"))
        assertTrue(SegmentIndex.read(dir).isEmpty())
        assertFalse(dir.exists()) // empty folder removed
        assertEquals(1, repo.sessions.value.size) // the night stays
    }

    @Test fun deletingAudioKeepsClips() {
        val repo = SessionRepository(tmp.root)
        val dir = repo.clipDir("m").apply { mkdirs() }
        File(dir, "c1.wav").writeBytes(ByteArray(10))
        File(dir, "audio-001.m4a").writeBytes(ByteArray(20))
        assertEquals(20L, repo.deleteNightAudio("m"))
        assertTrue(File(dir, "c1.wav").exists())
        assertEquals(0L, repo.deleteNightAudioOlderThan(1, nowMs = 0))
    }
}
