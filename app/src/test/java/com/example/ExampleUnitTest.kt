package com.example

import com.example.model.TrafficStats
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun trafficStats_formatsSpeedsCorrectly() {
        assertEquals("0 B/s", TrafficStats.formatSpeed(0))
        assertEquals("500 B/s", TrafficStats.formatSpeed(500))
        assertEquals("1.5 KB/s", TrafficStats.formatSpeed(1500))
        assertEquals("2.50 MB/s", TrafficStats.formatSpeed(2_500_000))
    }

    @Test
    fun trafficStats_formatsDurationCorrectly() {
        val stats1 = TrafficStats(connectedDurationSeconds = 45)
        assertEquals("00:45", stats1.formattedDuration)

        val stats2 = TrafficStats(connectedDurationSeconds = 3665)
        assertEquals("01:01:05", stats2.formattedDuration)
    }
}
