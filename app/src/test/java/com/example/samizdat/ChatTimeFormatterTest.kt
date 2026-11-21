package com.example.samizdat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ChatTimeFormatterTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance(utc).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun `same day shows HH mm in list and Today as date header`() {
        val now = millis(2026, Calendar.NOVEMBER, 21, 23, 7)
        val msg = millis(2026, Calendar.NOVEMBER, 21, 19, 29)

        assertTrue(ChatTimeFormatter.isSameCalendarDay(msg, now, utc))
        assertEquals("19:29", ChatTimeFormatter.formatConversationTimestamp(msg, now, utc, Locale.US))
        assertEquals("Today", ChatTimeFormatter.formatDateHeader(msg, now, utc, Locale.US))
    }

    @Test
    fun `yesterday shows Yesterday in both list and date header`() {
        val now = millis(2026, Calendar.NOVEMBER, 21, 23, 7)
        val msg = millis(2026, Calendar.NOVEMBER, 20, 22, 36)

        assertFalse(ChatTimeFormatter.isSameCalendarDay(msg, now, utc))
        assertEquals("Yesterday", ChatTimeFormatter.formatConversationTimestamp(msg, now, utc, Locale.US))
        assertEquals("Yesterday", ChatTimeFormatter.formatDateHeader(msg, now, utc, Locale.US))
    }

    @Test
    fun `earlier same year shows day and short month`() {
        val now = millis(2026, Calendar.NOVEMBER, 21, 23, 7)
        val msg = millis(2026, Calendar.NOVEMBER, 18, 22, 36)

        assertEquals("18 Nov", ChatTimeFormatter.formatConversationTimestamp(msg, now, utc, Locale.US))
        assertEquals("18 Nov", ChatTimeFormatter.formatDateHeader(msg, now, utc, Locale.US))
    }

    @Test
    fun `previous year includes year`() {
        val now = millis(2026, Calendar.NOVEMBER, 21, 23, 7)
        val msg = millis(2025, Calendar.DECEMBER, 5, 14, 15)

        assertEquals("5 Dec 2025", ChatTimeFormatter.formatConversationTimestamp(msg, now, utc, Locale.US))
        assertEquals("5 Dec 2025", ChatTimeFormatter.formatDateHeader(msg, now, utc, Locale.US))
    }
}
