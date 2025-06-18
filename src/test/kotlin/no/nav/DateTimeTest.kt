package no.nav.no.nav

import io.kotest.matchers.nulls.shouldNotBeNull
import no.nav.kafka.consumers.convertToOffsetDatetime
import org.junit.Test

class DateTimeTest {
    @Test
    fun testDateTimeParse() {
        val dateTimeString = "2025-04-10T13:01:14+02"
        val localDateTime = dateTimeString.convertToOffsetDatetime()
        localDateTime.shouldNotBeNull()
        println(localDateTime)
    }
}