package app.photos

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Understands dates typed into search (day first, as in India): "2023", "may", "may 2023", "05/2023", "2023-05",
 * "12/05/2023", "12-05-23", "12 may", "12 may 2023", "2023-05-12", and "today", "yesterday", "this month",
 * "last month", "this year", "last year". A missing part matches any value (e.g. "12 may" = 12 May of every year).
 * Same rules as desktop/src/datequery.js.
 */
data class DateQuery(val year: Int?, val month: Int?, val day: Int?) {
    fun matches(date: LocalDate) = (year == null || date.year == year) && (month == null || date.monthValue == month) && (day == null || date.dayOfMonth == day)

    /** e.g. "12 May 2023", "May 2023", "every 12 May", "2023". */
    fun label(): String {
        val m = month?.let { java.time.Month.of(it).getDisplayName(TextStyle.FULL, Locale.ENGLISH) }
        return when {
            year != null && m != null && day != null -> "$day $m $year"
            year != null && m != null -> "$m $year"
            m != null && day != null -> "$day $m, any year"
            m != null -> "$m, any year"
            year != null -> "$year"
            else -> ""
        }
    }

    companion object {
        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private fun month(word: String): Int? {
            val w = word.lowercase(Locale.ENGLISH).trimEnd('.')
            if (w.length < 3) return null
            val i = MONTHS.indexOfFirst { w.startsWith(it) && java.time.Month.of(MONTHS.indexOf(it) + 1).getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase().startsWith(w) }
            return if (i >= 0) i + 1 else null
        }
        private fun year(text: String): Int? {
            val n = text.toIntOrNull() ?: return null
            return when (text.length) { 4 -> n.takeIf { it in 1900..2100 }; 2 -> 2000 + n; else -> null }
        }
        private fun valid(y: Int?, m: Int?, d: Int?): DateQuery? {
            if (m != null && m !in 1..12) return null
            if (d != null && d !in 1..31) return null
            if (y != null && m != null && d != null && runCatching { LocalDate.of(y, m, d) }.isFailure) return null
            return DateQuery(y, m, d)
        }

        fun parse(input: String, today: LocalDate = LocalDate.now()): DateQuery? {
            val q = input.trim().lowercase(Locale.ENGLISH).replace(Regex("\\s+"), " ")
            if (q.isEmpty()) return null
            when (q) {
                "today" -> return DateQuery(today.year, today.monthValue, today.dayOfMonth)
                "yesterday" -> today.minusDays(1).let { return DateQuery(it.year, it.monthValue, it.dayOfMonth) }
                "this month" -> return DateQuery(today.year, today.monthValue, null)
                "last month" -> today.minusMonths(1).let { return DateQuery(it.year, it.monthValue, null) }
                "this year" -> return DateQuery(today.year, null, null)
                "last year" -> return DateQuery(today.year - 1, null, null)
            }
            // Numbers only: 2023 · 2023-05 · 2023-05-12 · 05/2023 · 12/05/2023 · 12-05-23
            Regex("(\\d{4})").matchEntire(q)?.let { return year(it.groupValues[1])?.let { y -> DateQuery(y, null, null) } }
            Regex("(\\d{4})[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?").matchEntire(q)?.let { m ->
                return valid(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull())
            }
            Regex("(\\d{1,2})[-/.](\\d{4})").matchEntire(q)?.let { m -> return valid(m.groupValues[2].toInt(), m.groupValues[1].toInt(), null) }
            Regex("(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2}|\\d{4})").matchEntire(q)?.let { m ->
                val y = year(m.groupValues[3]) ?: return null
                return valid(y, m.groupValues[2].toInt(), m.groupValues[1].toInt())
            }
            // With a month name: may · may 2023 · 2023 may · 12 may · 12 may 2023 · may 12 2023 · 12th may
            val words = q.replace(",", " ").split(' ').filter { it.isNotEmpty() }
            if (words.size > 3) return null
            var y: Int? = null; var m: Int? = null; var d: Int? = null
            for (w in words) {
                val number = w.removeSuffix("st").removeSuffix("nd").removeSuffix("rd").removeSuffix("th")
                when {
                    month(w) != null && m == null -> m = month(w)
                    number.length == 4 && year(number) != null && y == null -> y = year(number)
                    number.length in 1..2 && number.all(Char::isDigit) && d == null -> d = number.toInt()
                    else -> return null
                }
            }
            if (m == null) return null
            return valid(y, m, d)
        }
    }
}
