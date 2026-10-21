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

    @Test
    fun ssidQuoteStripping_cleansQuotesAndWhitespace() {
        val raw1 = "\"MyHomeWifi\""
        var s1 = raw1.trim()
        if (s1.startsWith("\"") && s1.endsWith("\"") && s1.length >= 2) {
            s1 = s1.substring(1, s1.length - 1)
        }
        assertEquals("MyHomeWifi", s1)

        val raw2 = "<unknown ssid>"
        val isInvalid = raw2 == "<unknown ssid>" || raw2 == "\"<unknown ssid>\""
        assertEquals(true, isInvalid)
    }

    @Test
    fun appBypassMode_valuesAreValid() {
        val modeA = com.example.model.AppBypassMode.valueOf("BYPASS_SELECTED")
        val modeB = com.example.model.AppBypassMode.valueOf("BYPASS_ALL_EXCEPT_SELECTED")
        assertEquals(com.example.model.AppBypassMode.BYPASS_SELECTED, modeA)
        assertEquals(com.example.model.AppBypassMode.BYPASS_ALL_EXCEPT_SELECTED, modeB)
    }
}
