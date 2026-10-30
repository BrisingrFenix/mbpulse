package pt.ua.mbpulse.collection

import pt.ua.mbpulse.collection.DeviceConnection.Display
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DeviceConnectionTest {

    @Before fun reset() = DeviceConnection.resetForTest()
    @After fun cleanUp() = DeviceConnection.resetForTest()

    private val now = 1_000_000_000L

    @Test
    fun `nothing reported and no samples is no data`() {
        assertEquals(Display.NO_DATA, DeviceConnection.display(DeviceConnection.status.value, now))
    }

    @Test
    fun `without reports recent samples mean receiving and old ones do not`() {
        DeviceConnection.onSample(now)
        assertEquals(Display.RECEIVING, DeviceConnection.display(DeviceConnection.status.value, now + 60_000))
        assertEquals(Display.NO_DATA,
            DeviceConnection.display(DeviceConnection.status.value, now + DeviceConnection.RECEIVING_WINDOW_MS + 1))
    }

    @Test
    fun `reported state wins over samples`() {
        DeviceConnection.onSample(now)
        DeviceConnection.report(DeviceConnectionState.CONNECTING)
        assertEquals(Display.CONNECTING, DeviceConnection.display(DeviceConnection.status.value, now))
        DeviceConnection.report(DeviceConnectionState.CONNECTED)
        assertEquals(Display.CONNECTED, DeviceConnection.display(DeviceConnection.status.value, now))
    }

    @Test
    fun `repeating a state keeps its since time`() {
        DeviceConnection.report(DeviceConnectionState.CONNECTED)
        val since = DeviceConnection.status.value.sinceMs
        Thread.sleep(5)
        DeviceConnection.report(DeviceConnectionState.CONNECTED)
        assertEquals(since, DeviceConnection.status.value.sinceMs)
    }

    @Test
    fun `stream end turns a reported link into disconnected but leaves an unreported one alone`() {
        DeviceConnection.onStreamEnded(now)
        assertNull(DeviceConnection.status.value.state)

        DeviceConnection.report(DeviceConnectionState.CONNECTED)
        DeviceConnection.onStreamEnded(now)
        assertEquals(DeviceConnectionState.DISCONNECTED, DeviceConnection.status.value.state)
        assertEquals(now, DeviceConnection.status.value.sinceMs)
    }

    @Test
    fun `samples update at most once per second`() {
        DeviceConnection.onSample(now)
        DeviceConnection.onSample(now + 500)
        assertEquals(now, DeviceConnection.status.value.lastSampleAtMs)
        DeviceConnection.onSample(now + 1_000)
        assertEquals(now + 1_000, DeviceConnection.status.value.lastSampleAtMs)
    }
}
