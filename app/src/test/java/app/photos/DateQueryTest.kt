package app.photos

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DateQueryTest {
    private val today = LocalDate.of(2026, 11, 20)
    private fun p(s: String) = DateQuery.parse(s, today)

    @Test fun years() { assertEquals(DateQuery(2023, null, null), p("2023")); assertNull(p("1234")) }
    @Test fun monthsAndDaysNumeric() {
        assertEquals(DateQuery(2023, 5, null), p("05/2023"))
        assertEquals(DateQuery(2023, 5, null), p("2023-05"))
        assertEquals(DateQuery(2023, 5, 12), p("12/05/2023"))
        assertEquals(DateQuery(2023, 5, 12), p("12-05-23"))
        assertEquals(DateQuery(2023, 5, 12), p("2023-05-12"))
        assertNull(p("31/02/2023"))
        assertNull(p("13/13/2023"))
    }
    @Test fun monthNames() {
        assertEquals(DateQuery(null, 5, null), p("May"))
        assertEquals(DateQuery(2023, 7, null), p("july 2023"))
        assertEquals(DateQuery(2023, 7, null), p("2023 jul"))
        assertEquals(DateQuery(null, 5, 12), p("12 may"))
        assertEquals(DateQuery(2023, 5, 12), p("12th May 2023"))
        assertEquals(DateQuery(2023, 5, 12), p("May 12, 2023"))
        assertNull(p("holiday"))
        assertNull(p("mayday"))
        assertNull(p("IMG_1234"))
    }
    @Test fun relative() {
        assertEquals(DateQuery(2026, 11, 20), p("today"))
        assertEquals(DateQuery(2026, 11, 19), p("yesterday"))
        assertEquals(DateQuery(2026, 10, null), p("last month"))
        assertEquals(DateQuery(2025, null, null), p("last year"))
    }
    @Test fun matching() {
        val q = p("12 may")!!
        assertTrue(q.matches(LocalDate.of(2019, 5, 12)))
        assertFalse(q.matches(LocalDate.of(2019, 5, 13)))
        assertEquals("12 May, any year", q.label())
        assertEquals("May 2023", p("may 2023")!!.label())
    }
}
