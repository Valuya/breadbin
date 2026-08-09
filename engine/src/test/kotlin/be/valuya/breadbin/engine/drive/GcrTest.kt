package be.valuya.breadbin.engine.drive

import be.valuya.breadbin.engine.disk.D64
import be.valuya.breadbin.engine.disk.Petscii
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encoding a real drive writes to a real disk.
 *
 * These can be checked exactly, without any of the rest of the drive, which is worth doing: if the
 * track a disk is turned into is not the track a 1541 would have written, the DOS will never find a
 * sector on it, and the failure will look like a timing problem rather than an encoding one.
 */
class GcrTest {

    @Test
    fun `four bytes go out as five and come back as four`() {
        val source = intArrayOf(0x01, 0x23, 0x45, 0x67)
        val encoded = IntArray(5)
        Gcr.encodeQuad(source, 0, encoded, 0)
        val decoded = IntArray(4)
        assertTrue(Gcr.decodeQuint(encoded, 0, decoded, 0))
        assertArrayEquals(source, decoded)
    }

    @Test
    fun `every byte survives the round trip`() {
        for (high in 0 until 256 step 7) {
            val source = intArrayOf(high, (high * 3) and 0xFF, (high xor 0xA5), (255 - high))
            val encoded = IntArray(5)
            Gcr.encodeQuad(source, 0, encoded, 0)
            val decoded = IntArray(4)
            assertTrue(Gcr.decodeQuint(encoded, 0, decoded, 0))
            assertArrayEquals(source, decoded)
        }
    }

    @Test
    fun `no encoded byte can be mistaken for a sync mark`() {
        // Sync is ten one bits in a row, and the point of the code is that data cannot produce
        // them: no nybble encodes to more than two ones at either end.
        for (a in 0 until 16) {
            for (b in 0 until 16) {
                val source = intArrayOf((a shl 4) or b, (a shl 4) or b, (a shl 4) or b, (a shl 4) or b)
                val encoded = IntArray(5)
                Gcr.encodeQuad(source, 0, encoded, 0)
                var run = 0
                var longest = 0
                for (byte in encoded) {
                    for (bit in 7 downTo 0) {
                        if (byte shr bit and 1 == 1) run++ else run = 0
                        longest = maxOf(longest, run)
                    }
                }
                assertTrue("$a/$b encodes to a run of $longest ones", longest < 10)
            }
        }
    }

    @Test
    fun `a track is the length the drive expects and holds every sector`() {
        val disk = D64.blank(Petscii.fromAscii("TEST"), Petscii.fromAscii("01"))
        disk.writeFile(Petscii.fromAscii("HELLO"), IntArray(2000) { it and 0xFF }, 2, replace = false)

        for (track in intArrayOf(1, 17, 18, 24, 25, 30, 31, 35)) {
            val gcr = Gcr.buildTrack(disk, track, 0x30, 0x31)
            assertTrue(
                "track $track came out ${gcr.size} bytes",
                gcr.size >= Gcr.trackLength(track) - 32 && gcr.size <= Gcr.trackLength(track),
            )
            // Reading the track back must find every sector on it.
            val recovered = D64.blank(Petscii.fromAscii("BLANK"), Petscii.fromAscii("02"))
            val found = Gcr.decodeTrack(gcr, track, recovered)
            assertEquals("track $track", D64.sectorsPerTrack(track), found)
            for (sector in 0 until D64.sectorsPerTrack(track)) {
                assertArrayEquals(
                    "track $track sector $sector",
                    disk.read(track, sector),
                    recovered.read(track, sector),
                )
            }
        }
    }

    @Test
    fun `a header with a broken checksum is left alone`() {
        val disk = D64.blank(Petscii.fromAscii("TEST"), Petscii.fromAscii("01"))
        val gcr = Gcr.buildTrack(disk, 18, 0x30, 0x31)

        // Corrupt one sector's header checksum. The data block behind it is still perfectly good,
        // which is what makes this dangerous: without the check it would be written somewhere.
        val header = IntArray(8)
        var at = 0
        while (gcr[at] != 0xFF) at++
        while (gcr[at] == 0xFF) at++
        assertTrue(Gcr.decodeQuint(gcr, at, header, 0))
        header[1] = header[1] xor 0xFF
        Gcr.encodeQuad(header, 0, gcr, at)

        val recovered = D64.blank(Petscii.fromAscii("BLANK"), Petscii.fromAscii("02"))
        val found = Gcr.decodeTrack(gcr, 18, recovered)
        assertEquals(D64.sectorsPerTrack(18) - 1, found)
    }

    @Test
    fun `the density and the byte rate go together`() {
        assertEquals(3, Gcr.densityOf(1))
        assertEquals(0, Gcr.densityOf(35))
        // Three hundred revolutions a minute, so a track goes past in a fifth of a second: the
        // byte rate and the track length have to agree about that or the sectors will not fit.
        for (track in 1..35) {
            val bytes = Gcr.trackLength(track)
            val cycles = bytes.toDouble() * Gcr.cyclesPerByte(Gcr.densityOf(track))
            assertEquals("track $track takes ${cycles / 1_000_000} seconds a turn", 0.2, cycles / 1_000_000, 0.005)
        }
    }
}
