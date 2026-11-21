package com.example.mygarmincaloriestracker

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

/** Calendar periods with inclusive endpoints and Monday as the first day of the week. */
@RequiresApi(26)
internal enum class StatsPeriod(val label: String) {
    DAY("Tag"), WEEK("Woche"), MONTH("Monat"), YEAR("Jahr");

    fun range(anchor: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
        DAY -> anchor to anchor
        WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
        MONTH -> anchor.withDayOfMonth(1) to anchor.withDayOfMonth(anchor.lengthOfMonth())
        YEAR -> anchor.withDayOfYear(1) to anchor.withDayOfYear(anchor.lengthOfYear())
    }

    fun move(anchor: LocalDate, step: Int): LocalDate = when (this) {
        DAY -> anchor.plusDays(step.toLong())
        WEEK -> anchor.plusWeeks(step.toLong())
        MONTH -> anchor.plusMonths(step.toLong())
        YEAR -> anchor.plusYears(step.toLong())
    }
}

internal enum class StatsTarget { ENERGY, CHANGE, BODY }

/** Most recent actual measurement for each metric, with its own measurement date. */
internal data class LatestMeasurements(
    val weight: Pair<LocalDate, Double>?,
    val waist: Pair<LocalDate, Double>?,
    val hip: Pair<LocalDate, Double>?,
)

@RequiresApi(26)
internal class TrackerViews(private val context: Context) {
    private val german = Locale.GERMANY
    private val fullDate = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", german)
    private val shortDate = DateTimeFormatter.ofPattern("dd.MM.", german)
    private val dateWithYear = DateTimeFormatter.ofPattern("dd.MM.yyyy", german)
    private val monthDate = DateTimeFormatter.ofPattern("MMMM yyyy", german)
    private val monthName = DateTimeFormatter.ofPattern("MMMM", german)
    private val clock = DateTimeFormatter.ofPattern("HH:mm", german)
    private val surface = color(MaterialR.attr.colorSurface, 0xFFFFFFFF.toInt())
    private val dark = ColorUtils.calculateLuminance(surface) < 0.35
    private val ink = color(MaterialR.attr.colorOnSurface, if (dark) 0xFFF7F3FA.toInt() else 0xFF201A25.toInt())
    private val primary = color(MaterialR.attr.colorPrimary, if (dark) 0xFFD0BCFF.toInt() else 0xFF624798.toInt())
    private val onPrimary = color(MaterialR.attr.colorOnPrimary, if (dark) 0xFF241442.toInt() else 0xFFFFFFFF.toInt())
    private val muted = ColorUtils.blendARGB(surface, ink, 0.76f)
    private val outline = ColorUtils.blendARGB(surface, ink, 0.16f)
    private val tint = ColorUtils.blendARGB(surface, primary, 0.09f)
    private val backdrop = ColorUtils.blendARGB(color(android.R.attr.colorBackground, surface), primary, 0.035f)
    private val burnColor = if (dark) 0xFF72D8E3.toInt() else 0xFF006573.toInt()
    private val activeColor = if (dark) 0xFFFFBD73.toInt() else 0xFF9A4D00.toInt()
    private val everydayColor = if (dark) 0xFFB8C5D8.toInt() else 0xFF536882.toInt()
    private val gain = if (dark) 0xFFFFB4AB.toInt() else 0xFFA12C32.toInt()
    private val loss = if (dark) 0xFF89DAA6.toInt() else 0xFF21683F.toInt()

    fun dashboard(
        data: DayData,
        now: LocalDateTime,
        onDeleteMeal: (Long) -> Unit,
        onDeleteActivity: (Long) -> Unit,
        onSettings: () -> Unit,
        streak: Int = 0,
        measurements: LatestMeasurements? = null,
        onStatistics: (StatsTarget) -> Unit,
        onEditMeal: (Meal) -> Unit,
    ): View = screen(bottom = 104) {
        val cutoffDate = minOf(data.day, now.toLocalDate())
        fun latestMetric(provided: Pair<LocalDate, Double>?, value: (Body) -> Double?): Pair<LocalDate, Double>? =
            (listOfNotNull(provided) + listOfNotNull(data.body, data.latestBody).mapNotNull { body ->
                value(body)?.let { body.day to it }
            }).filter { (date, amount) -> date <= cutoffDate && amount.isFinite() && amount > 0 }
                .maxByOrNull { it.first }
        val weight = latestMetric(measurements?.weight) { it.weight }
        val waist = latestMetric(measurements?.waist) { it.waist }
        val hip = latestMetric(measurements?.hip) { it.hip }
        val energy = TrackerMath.energy(data, now)
        val intake = recordedIntake(data, now, energy)
        addView(label(if (data.day == now.toLocalDate()) "Tagesübersicht" else "Tagesprotokoll", 14, primary, bold = true))
        addView(title(fullDate.format(data.day), 24), margins(top = 4))
        addView(button("Einstellungen", onSettings), margins(top = 8, width = WRAP_CONTENT))

        card("Energiebilanz") {
            val max = maxOf(intake ?: 0.0, energy.burned ?: 0.0, 1.0)
            energyBar("Erfasste Aufnahme", intake, max, primary, "Noch nichts erfasst")
            val parts = listOfNotNull(
                energy.resting?.let { BurnPart(if (energy.source == "Manuell") "Basisverbrauch" else "Ruhekalorien", it, burnColor) },
                energy.everyday?.let { BurnPart("Geschätzter Alltag", it, everydayColor) },
                energy.active?.let { BurnPart("Aktivkalorien", it, activeColor) },
            )
            val hasSplit = energy.burned != null && energy.resting != null && energy.active != null
            val missing = if (energy.source == "Garmin" && energy.resting != null && energy.active == null)
                "Aktivkalorien fehlen – Gesamtwert unbekannt" else "Keine Verbrauchsdaten"
            energyBar("Verbrauch", energy.burned, max, burnColor, missing, if (hasSplit) parts else emptyList())
            if (hasSplit || (energy.burned == null && parts.isNotEmpty())) {
                parts.forEach { part ->
                    addView(label("●  ${part.name}: ${quantity(part.kcal, "kcal")}", 14, part.color), margins(top = 5))
                }
            } else if (energy.source == "Garmin" && energy.burned != null) {
                addView(label("Keine verlässliche Aufteilung verfügbar.", 13, muted), margins(top = 5))
            }
        }.statisticsLink("Energieverlauf") { onStatistics(StatsTarget.ENERGY) }
        card("Geschätzte Gewichtsänderung", tinted = true) {
            val change = energy.changeKg?.takeIf { it.isFinite() && (it * 1000).isFinite() }
            if (change != null) {
                val (arrow, word, shade) = direction(change)
                val grams = change * 1000
                val estimate = if (grams != 0.0 && kotlin.math.abs(grams) < 0.1) {
                    "${if (grams > 0) "+" else "−"}< 0,1 g"
                } else quantity(grams, "g", 1, signed = true)
                addView(label("$arrow $estimate", 32, shade, bold = true), margins(top = 8))
                addView(label("$word · Energiebilanz ${quantity(energy.balance, "kcal", signed = true)}", 15, shade, bold = true), margins(top = 4))
            } else {
                addView(label("Noch keine Schätzung", 19, bold = true), margins(top = 8))
                addView(label("Dafür werden erfasste Mahlzeiten und Verbrauchsdaten benötigt.", 14, muted), margins(top = 4))
            }
            addView(label("Grober Richtwert aus der Energiebilanz, kein gemessenes Gewicht.", 13, muted), margins(top = 10))
        }.statisticsLink("Verlauf der geschätzten Gewichtsänderung") { onStatistics(StatsTarget.CHANGE) }
        card("Körpermaße") {
            metric("Gewicht", weight?.second, "kg", weight?.first)
            metric("Taille", waist?.second, "cm", waist?.first)
            metric("Hüfte", hip?.second, "cm", hip?.first)
            val height = data.profile.height?.takeIf { it.isFinite() && it > 0 }
            val meters = height?.div(100.0)
            val bmi = if (weight != null && meters != null) {
                (weight.second / (meters * meters)).takeIf { it.isFinite() && it > 0 }
            } else null
            if (bmi != null) {
                addView(label("BMI: ${quantity(bmi, "", 1).trimEnd()} (Größe ${quantity(height, "cm", 1)})", 14, muted), margins(top = 8))
            } else {
                val hint = when {
                    height == null && weight == null -> "Für den BMI Gewicht erfassen und Größe in den Einstellungen hinterlegen."
                    height == null -> "Für den BMI Größe in den Einstellungen hinterlegen."
                    weight == null -> "Für den BMI Gewicht erfassen."
                    else -> "BMI mit den vorhandenen Werten nicht verfügbar."
                }
                addView(label(hint, 13, muted), margins(top = 8))
            }
            if (weight == null && waist == null && hip == null) {
                addView(label("Trage über + deine erste Messung ein.", 14, muted), margins(top = 10))
            }
        }.statisticsLink("Verlauf der Körpermaße") { onStatistics(StatsTarget.BODY) }
        card("Prognose und Erfolge", tinted = true) {
            val isToday = data.day == now.toLocalDate()
            addView(label("Quelle: ${energy.source}", 15, bold = true), margins(top = 8))
            if (isToday) {
                val forecast = energy.forecast?.takeIf { it.isFinite() && it >= 0 }
                if (forecast != null) {
                    addView(label("Verbrauch bis Tagesende: ${quantity(forecast, "kcal")}", 17, bold = true), margins(top = 8))
                    if (intake != null && intake.isFinite()) {
                        val difference = energy.intake - forecast
                        val direction = when {
                            difference < 0 -> "Voraussichtliches Defizit"
                            difference > 0 -> "Voraussichtlicher Überschuss"
                            else -> "Ausgeglichene Bilanz"
                        }
                        val shade = when { difference < 0 -> loss; difference > 0 -> gain; else -> muted }
                        addView(label("$direction: ${quantity(difference, "kcal", signed = true)}", 16, shade, bold = true), margins(top = 5))
                    } else {
                        addView(label("Für eine Bilanzprognose fehlt eine erfasste Mahlzeit.", 14, muted), margins(top = 5))
                    }
                    addView(label("Vergleich mit bisher erfasster Aufnahme – sie ist möglicherweise noch nicht vollständig. Weitere Mahlzeiten oder Trainings werden nicht vorhergesagt.${if (energy.source == "Garmin") " Garmin-Hochrechnung aus letztem importiertem Stand; verzögerte Daten können die Prognose verzerren." else ""}", 13, muted), margins(top = 7))
                } else {
                    addView(label("Derzeit keine Verbrauchsprognose verfügbar.", 14, muted), margins(top = 6))
                }
            }
            val completedStreak = streak.coerceAtLeast(0)
            addView(View(context).apply { setBackgroundColor(outline) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(14) })
            val streakEnd = minOf(data.day, now.toLocalDate().minusDays(1))
            val streakLabel = if (data.day == now.toLocalDate()) "Defizit-Serie bis gestern" else "Defizit-Serie bis ${dateWithYear.format(streakEnd)}"
            addView(label(streakLabel, 15, bold = true), margins(top = 12))
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(completedStreak.toString(), 32, if (completedStreak > 0) primary else muted, bold = true))
                addView(label(if (completedStreak == 1) "  Tag in Folge" else "  Tage in Folge", 15, muted))
            }, margins(top = 2))
            if (completedStreak > 0) {
                addView(row().apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    repeat(minOf(completedStreak, 7)) { index ->
                        addView(View(context).apply { background = rounded(primary, 12, primary) },
                            LinearLayout.LayoutParams(dp(12), dp(12)).apply { if (index > 0) marginStart = dp(5) })
                    }
                }, margins(top = 8))
            } else {
                addView(label("Keine bestätigte Serie für diesen Zeitraum.", 14, muted), margins(top = 4))
            }
            addView(label("Nur abgeschlossene Tage mit ausreichenden Daten zählen; fehlende Daten sind keine Bewertung.", 13, muted), margins(top = 7))
            if (data.profile.useGarmin && data.garmin?.total == null) {
                val syncedActive = data.garmin?.active
                val detail = if (syncedActive != null) " Importierte Garmin-Aktivkalorien: ${quantity(syncedActive, "kcal")}." else ""
                addView(label("Kein vollständiger Garmin-Verbrauch verfügbar.$detail", 13, muted), margins(top = 6))
            }
            if (energy.source == "Garmin" && data.profile.garminTotalIsResting) {
                addView(label("Garmin-Verbrauch = importierte Ruhekalorien + importierte Aktivkalorien.", 13, muted), margins(top = 6))
            }
        }
        card("Mahlzeiten") {
            if (data.meals.isEmpty()) {
                empty("Noch keine Mahlzeiten", "Tippe auf +, um etwas zu erfassen.")
            } else {
                data.meals.sortedBy { it.minute }.forEach { meal ->
                    val detail = listOfNotNull(time(meal.minute),
                        if (meal.isDirect) null else quantity(meal.grams, "g", 1), quantity(meal.kcal, "kcal")).joinToString(" · ")
                    entry(meal.name, detail,
                        deleteLabel = "Mahlzeit ${meal.name} löschen", delete = { onDeleteMeal(meal.id) },
                        editLabel = if (meal.isDirect) "Kalorien von ${meal.name} bearbeiten" else "Menge von ${meal.name} bearbeiten", edit = { onEditMeal(meal) })
                }
            }
        }
        card("Aktivitäten") {
            val entries = (data.activities.map { it to false } + data.garmin?.activities.orEmpty().map { it to true })
                .sortedBy { it.first.minute }
            if (entries.isEmpty()) {
                empty("Noch keine Aktivitäten", "Füge über + eine Aktivität hinzu oder importiere sie aus Garmin.")
            } else {
                entries.forEach { (activity, imported) ->
                    val source = if (imported) {
                        activity.source.takeUnless { it.isBlank() || it == "Manuell" || it == "Garmin" }
                            ?.let { "Garmin ($it)" } ?: "Garmin"
                    } else "Manuell"
                    val detail = listOfNotNull(time(activity.minute), "${activity.minutes} min",
                        quantity(activity.kcal, "kcal").takeIf { !imported || activity.kcal > 0 }, source).joinToString(" · ")
                    if (imported) entry(activity.name, detail)
                    else entry(activity.name, detail, "Aktivität ${activity.name} löschen") { onDeleteActivity(activity.id) }
                }
                if (entries.any { it.second }) {
                    addView(label("Garmin-Aktivitäten werden angezeigt und nicht zusätzlich addiert.", 13, muted), margins(top = 8))
                }
            }
        }
    }

    fun statistics(
        data: List<DayData>,
        now: LocalDateTime,
        period: StatsPeriod,
        anchor: LocalDate,
        onPeriod: (StatsPeriod) -> Unit,
        onStep: (Int) -> Unit,
        target: StatsTarget? = null,
    ): View = screen(bottom = 24) {
        val (from, to) = period.range(anchor)
        val today = now.toLocalDate()
        val yearly = period == StatsPeriod.YEAR
        val slots = if (yearly) 12 else ChronoUnit.DAYS.between(from, to).toInt() + 1
        val metrics = listOf(
            Metric("Aufnahme", "kcal", primary, 0), Metric("Verbrauch", "kcal", burnColor, 0),
            Metric("Geschätzte Gewichtsänderung", "kg", ink, 2, signed = true),
            Metric("Gewicht", "kg", primary, 2), Metric("Taille", "cm", primary, 1), Metric("Hüfte", "cm", primary, 1),
        )
        val samples = List(metrics.size) { arrayOfNulls<Sample>(slots) }
        // One pass over the supplied days. Sum available energy values; retain the latest actual
        // measurement independently for each body metric. Null slots never become zeroes.
        data.forEach { day ->
            if (day.day < from || day.day > to || day.day > today) return@forEach
            val index = if (yearly) day.day.monthValue - 1 else ChronoUnit.DAYS.between(from, day.day).toInt()
            val energy = TrackerMath.energy(day, now)
            val body = day.body?.takeIf { it.day == day.day }
            val values = listOf(recordedIntake(day, now, energy), energy.burned, energy.changeKg, body?.weight, body?.waist, body?.hip)
            values.forEachIndexed { metric, value ->
                if (value == null || !value.isFinite()) return@forEachIndexed
                val old = samples[metric][index]
                samples[metric][index] = when {
                    yearly && metric < 3 && old != null -> Sample(old.value + value, maxOf(day.day, old.date), old.count + 1)
                    old == null || day.day >= old.date -> Sample(value, day.day)
                    else -> old
                }
            }
        }

        addView(title("Statistik", 24))
        StatsPeriod.entries.chunked(2).forEach { options ->
            addView(buttonPair(options.map { item -> button(item.label, { onPeriod(item) }, selected = item == period) }), margins(top = 6))
        }
        val interval = when (period) {
            StatsPeriod.DAY -> fullDate.format(from)
            StatsPeriod.MONTH -> monthDate.format(from)
            StatsPeriod.YEAR -> from.year.toString()
            StatsPeriod.WEEK -> "${if (from.year == to.year) shortDate.format(from) else dateWithYear.format(from)} – ${dateWithYear.format(to)}"
        }
        addView(title(interval, 19).apply { gravity = Gravity.CENTER }, margins(top = 14))
        addView(buttonPair(listOf(
            button("‹ Zurück", { onStep(-1) }, description = "Vorheriger Zeitraum: ${period.label}"),
            button("Weiter ›", { onStep(1) }, enabled = to < today, description = "Nächster Zeitraum: ${period.label}"),
        )), margins(top = 6))
        addView(label(when (period) {
            StatsPeriod.DAY -> if (from == today) "Tageswerte für heute zeigen den Stand um ${clock.format(now)} Uhr."
                else "Tageswerte und erfasste Mahlzeiten nach Uhrzeit."
            StatsPeriod.YEAR -> "Monatssummen aus Tagen mit Daten; bei Körpermaßen zählt je Messreihe der letzte Messwert des Monats."
            else -> "Ein Punkt pro Tag; für heute werden nur die bisher verfügbaren Werte gezeigt."
        }, 14, muted), margins(top = 10))
        addView(label("Fehlende Energiewerte bleiben Lücken. Nicht erfasste Mahlzeiten zählen nicht als 0 kcal. Körpermaße verbinden aufeinanderfolgende Messungen.", 13, muted), margins(top = 5))

        val ticks = when {
            yearly -> listOf("Jan", "Jun / Jul", "Dez")
            slots == 1 -> listOf(shortDate.format(from))
            slots % 2 == 1 -> listOf(from, from.plusDays(slots.toLong() / 2), to).map(shortDate::format)
            else -> listOf(shortDate.format(from), "", shortDate.format(to))
        }
        fun series(index: Int) = Series(metrics[index], samples[index])
        card("Energieverlauf") {
            addView(label("● Aufnahme  ·  ■ Verbrauch", 14, muted), margins(top = 8))
            graph(listOf(series(0), series(1)), ticks, from, yearly, includeZero = true)
        }.tag = StatsTarget.ENERGY
        if (period == StatsPeriod.DAY) {
            val mealsByHour = arrayOfNulls<Sample>(24)
            val cutoff = if (from == today) now.hour * 60 + now.minute else 1439
            if (from <= today) {
                data.firstOrNull { it.day == from }?.meals?.forEach { meal ->
                    if (meal.minute in 0..cutoff && meal.kcal.isFinite()) {
                        val hour = meal.minute / 60
                        mealsByHour[hour] = Sample((mealsByHour[hour]?.value ?: 0.0) + meal.kcal, from)
                    }
                }
            }
            card("Aufnahme nach Uhrzeit") {
                addView(label("Summe der erfassten Mahlzeiten je Stunde. Für den Verbrauch gibt es nur einen Tageswert.", 14, muted), margins(top = 8))
                graph(listOf(Series(metrics[0], mealsByHour)), listOf("00 Uhr", "", "23 Uhr"), from,
                    yearly = false, includeZero = true, hourly = true)
            }
        }
        card("Geschätzte Gewichtsänderung") {
            addView(label("↑ Zunahme · ↓ Abnahme · aus der Energiebilanz, keine Messung", 14, muted), margins(top = 8))
            graph(listOf(series(2)), ticks, from, yearly, includeZero = true, highlightZero = true)
        }.tag = StatsTarget.CHANGE
        addView(title("Gemessene Körpermaße", 20), margins(top = 22))
        (3..5).forEach { index ->
            card(metrics[index].name) { graph(listOf(series(index)), ticks, from, yearly, includeZero = false, connectGaps = true) }
                .apply { if (index == 3) tag = StatsTarget.BODY }
        }
    }.apply {
        if (target != null) doOnLayout {
            post {
                findViewWithTag<View>(target)?.let { destination ->
                    val bounds = Rect(0, 0, destination.width, destination.height)
                    offsetDescendantRectToMyCoords(destination, bounds)
                    scrollTo(0, bounds.top)
                }
            }
        }
    }

    private fun recordedIntake(day: DayData, now: LocalDateTime, energy: Energy): Double? {
        val today = now.toLocalDate()
        val cutoff = now.hour * 60 + now.minute
        return energy.intake.takeIf { day.day <= today && day.meals.any { day.day < today || it.minute <= cutoff } }
    }

    private data class Trend(val arrow: String, val word: String, val color: Int)

    private fun direction(value: Double): Trend = when {
        value > 0 -> Trend("↑", "Zunahme", gain)
        value < 0 -> Trend("↓", "Abnahme", loss)
        else -> Trend("→", "Unverändert", muted)
    }

    private data class Sample(val value: Double, val date: LocalDate, val count: Int = 1)
    private data class Metric(val name: String, val unit: String, val color: Int, val digits: Int, val signed: Boolean = false)
    private class Series(val metric: Metric, val samples: Array<Sample?>)

    private fun LinearLayout.graph(
        series: List<Series>, ticks: List<String>, from: LocalDate, yearly: Boolean, includeZero: Boolean,
        hourly: Boolean = false, highlightZero: Boolean = false, connectGaps: Boolean = false,
    ) {
        val values = series.flatMap { line -> line.samples.mapNotNull { it?.value } }
        if (values.isEmpty()) {
            addView(label("Keine Werte in diesem Zeitraum.", 14, muted), margins(top = 10))
            return
        }
        val firstMetric = series.first().metric
        var min = values.minOrNull() ?: 0.0
        var max = values.maxOrNull() ?: 0.0
        if (includeZero) {
            min = minOf(0.0, min)
            max = maxOf(0.0, max)
        }
        if (min == max) {
            val delta = when {
                firstMetric.unit == "kcal" -> 1.0
                firstMetric.signed -> 0.01
                else -> 0.5
            }
            if (!includeZero || firstMetric.signed) min -= delta
            max += delta
        }
        val plot = Plot(series, min, max, highlightZero, connectGaps)
        addView(plot, LinearLayout.LayoutParams(MATCH_PARENT, dp(120)).apply { topMargin = dp(10) })
        addView(row().apply {
            setPadding(plot.axisInset, 0, dp(6), 0)
            ticks.forEachIndexed { index, caption ->
                addView(label(caption, 12, muted).apply {
                    gravity = when {
                        ticks.size == 1 || index == 1 -> Gravity.CENTER
                        index == 0 -> Gravity.START
                        else -> Gravity.END
                    }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        })
        val details = column()
        details.visibility = View.GONE
        val toggle = button("Werte anzeigen", {}, description = "Werte für ${series.joinToString(" und ") { it.metric.name }} anzeigen")
        toggle.setOnClickListener {
            val open = details.visibility != View.VISIBLE
            if (open && details.childCount == 0) {
                series.first().samples.indices.forEach { index ->
                    val entries = series.mapNotNull { line ->
                        val sample = line.samples[index] ?: return@mapNotNull null
                        val metric = line.metric
                        val value = quantity(sample.value, metric.unit, metric.digits, metric.signed)
                        val note = when {
                            !yearly -> ""
                            includeZero -> if (sample.count == 1) " (1 Tag mit Wert)" else " (${sample.count} Tage mit Wert)"
                            else -> " (gemessen am ${shortDate.format(sample.date)})"
                        }
                        if (series.size > 1) "${metric.name}: $value$note" else "$value$note"
                    }
                    if (entries.isNotEmpty()) {
                        val caption = when {
                            hourly -> time(index * 60)
                            yearly -> monthName.format(from.plusMonths(index.toLong()))
                            else -> shortDate.format(from.plusDays(index.toLong()))
                        }
                        details.addView(label("$caption · ${entries.joinToString(" · ")}", 14), margins(top = 6))
                    }
                }
            }
            details.visibility = if (open) View.VISIBLE else View.GONE
            val verb = if (open) "ausblenden" else "anzeigen"
            toggle.text = "Werte $verb"
            toggle.contentDescription = "Werte für ${series.joinToString(" und ") { it.metric.name }} $verb"
        }
        addView(toggle, margins(top = 6, width = WRAP_CONTENT))
        addView(details, margins(top = 2))
    }

    /** The plot is paired with a native text list, so values aren't trapped in the canvas. */
    private inner class Plot(
        private val series: List<Series>, private val low: Double, private val high: Double,
        private val highlightZero: Boolean, connectGaps: Boolean,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
            textAlign = Paint.Align.RIGHT
            color = muted
        }
        private val dash = DashPathEffect(floatArrayOf(dp(5).toFloat(), dp(4).toFloat()), 0f)
        private val tickValues = listOf(high, low + (high - low) / 2, low)
        private val tickLabels = tickValues.map(::formatAxis)
        private val zeroLabel = formatAxis(0.0)
        val axisInset: Int = (maxOf(tickLabels.maxOf { textPaint.measureText(it) }, textPaint.measureText(zeroLabel)) + dp(12)).toInt()
        private val segments = series.map { line -> chartSegments(line.samples.map { it != null }, connectGaps) }

        private fun formatAxis(value: Double): String {
            val metric = series.first().metric
            return quantity(value, metric.unit, metric.digits, metric.signed)
        }

        init { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = maxOf(dp(6).toFloat(), (textPaint.descent() - textPaint.ascent()) / 2)
            val left = axisInset.toFloat().coerceAtMost(width.toFloat())
            val right = (width - dp(6).toFloat()).coerceAtLeast(left)
            val span = (height - 2 * pad).coerceAtLeast(0f)
            val slots = series.first().samples.size
            fun x(index: Int) = if (slots == 1) (left + right) / 2f else left + (right - left) * index / (slots - 1)
            fun y(value: Double) = pad + (1.0 - (value - low) / (high - low)).toFloat() * span
            fun axisLabel(text: String, lineY: Float, color: Int, bold: Boolean = false) {
                textPaint.color = color
                textPaint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                val baseline = lineY - (textPaint.ascent() + textPaint.descent()) / 2
                canvas.drawText(text, left - dp(6), baseline, textPaint)
            }
            val zeroY = if (highlightZero && low <= 0.0 && high >= 0.0) y(0.0) else null
            val labelHeight = textPaint.descent() - textPaint.ascent()
            tickValues.forEachIndexed { index, value ->
                val lineY = y(value)
                if (zeroY == null || kotlin.math.abs(lineY - zeroY) >= labelHeight) {
                    paint.color = outline
                    paint.strokeWidth = dp(1).toFloat()
                    canvas.drawLine(left, lineY, right, lineY, paint)
                    axisLabel(tickLabels[index], lineY, muted)
                }
            }
            if (zeroY != null) {
                paint.color = ink
                paint.strokeWidth = dp(2).toFloat()
                canvas.drawLine(left, zeroY, right, zeroY, paint)
                axisLabel(zeroLabel, zeroY, ink, bold = true)
            }
            series.forEachIndexed { layer, line ->
                paint.color = line.metric.color
                paint.strokeWidth = dp(2).toFloat()
                paint.pathEffect = if (layer == 1) dash else null
                segments[layer].forEach { (first, second) ->
                    val previous = line.samples[first] ?: return@forEach
                    val current = line.samples[second] ?: return@forEach
                    canvas.drawLine(x(first), y(previous.value), x(second), y(current.value), paint)
                }
                paint.pathEffect = null
                line.samples.forEachIndexed { index, sample ->
                    if (sample == null) return@forEachIndexed
                    val px = x(index)
                    val py = y(sample.value)
                    if (layer == 1) {
                        val half = dp(3).toFloat()
                        canvas.drawRect(px - half, py - half, px + half, py + half, paint)
                    } else {
                        paint.color = if (line.metric.signed) direction(sample.value).color else line.metric.color
                        canvas.drawCircle(px, py, dp(4).toFloat(), paint)
                    }
                }
            }
        }
    }

    private fun screen(bottom: Int, content: LinearLayout.() -> Unit): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        setBackgroundColor(backdrop)
        addView(column().apply {
            setPadding(dp(16), dp(16), dp(16), dp(bottom))
            content()
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun LinearLayout.card(name: String, tinted: Boolean = false, content: LinearLayout.() -> Unit): LinearLayout {
        val card = column().apply {
            background = rounded(if (tinted) tint else surface, 18, outline)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(title(name, 19))
            content()
        }
        addView(card, margins(top = 12))
        return card
    }

    private fun LinearLayout.statisticsLink(destination: String, click: () -> Unit) {
        fun accessibleText(view: View): List<String> = when (view) {
            is TextView -> listOf(view.text.toString())
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { accessibleText(view.getChildAt(it)) }
            else -> emptyList()
        }
        contentDescription = (accessibleText(this) + "Öffnet $destination in der Statistik").joinToString(". ")
        minimumHeight = dp(48)
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 38)), background, null)
        ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        })
        setOnClickListener { click() }
    }

    private data class BurnPart(val name: String, val kcal: Double, val color: Int)

    private fun LinearLayout.energyBar(name: String, value: Double?, max: Double, shade: Int, missing: String,
                                       parts: List<BurnPart> = emptyList()) {
        addView(label(name, 14, muted), margins(top = 12))
        addView(label(if (value == null) missing else quantity(value, "kcal"), 18, bold = true), margins(top = 2))
        val fraction = ((value ?: 0.0) / max).coerceIn(0.0, 1.0).toFloat()
        addView(object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val path = android.graphics.Path()
            override fun onDraw(canvas: Canvas) {
                val radius = height / 2f
                paint.color = outline
                canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
                if (fraction > 0f) {
                    val end = width * fraction
                    if (parts.isEmpty()) {
                        paint.color = shade
                        canvas.drawRoundRect(0f, 0f, end, height.toFloat(), radius, radius, paint)
                    } else {
                        path.rewind()
                        path.addRoundRect(0f, 0f, end, height.toFloat(), radius, radius, android.graphics.Path.Direction.CW)
                        val saved = canvas.save()
                        canvas.clipPath(path)
                        var left = 0f
                        parts.forEachIndexed { index, part ->
                            val right = if (index == parts.lastIndex) end else (left + width * (part.kcal / max).toFloat()).coerceAtMost(end)
                            paint.color = part.color
                            canvas.drawRect(left, 0f, right, height.toFloat(), paint)
                            left = right
                        }
                        canvas.restoreToCount(saved)
                    }
                }
            }
        }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(8)).apply { topMargin = dp(6) })
    }

    private fun LinearLayout.metric(name: String, value: Double?, unit: String, date: LocalDate?) {
        val suffix = if (date != null) " · ${dateWithYear.format(date)}" else ""
        val displayed = if (value == null) "Nicht erfasst" else quantity(value, unit, if (unit == "kg") 2 else 1) + suffix
        addView(label("$name: $displayed", 16, bold = value != null), margins(top = 8))
    }

    private fun LinearLayout.empty(heading: String, detail: String) {
        addView(label(heading, 16, bold = true), margins(top = 10))
        addView(label(detail, 14, muted), margins(top = 4))
    }

    private fun LinearLayout.entry(name: String, detail: String, deleteLabel: String? = null,
                                   editLabel: String? = null, edit: (() -> Unit)? = null,
                                   delete: (() -> Unit)? = null) {
        addView(View(context).apply { setBackgroundColor(outline) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(10) })
        addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(column().apply {
                addView(label(name, 16, bold = true))
                addView(label(detail, 13, muted), margins(top = 3))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            fun iconAction(icon: Int, description: String?, action: () -> Unit) = ImageButton(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(primary)
                setBackgroundResource(android.R.drawable.list_selector_background)
                contentDescription = description
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { action() }
            }
            if (edit != null) addView(iconAction(R.drawable.ic_edit_tracker, editLabel, edit), LinearLayout.LayoutParams(dp(48), dp(48)))
            if (delete != null) addView(iconAction(R.drawable.ic_delete_tracker, deleteLabel, delete), LinearLayout.LayoutParams(dp(48), dp(48)))
        }, margins(top = 8))
    }

    private fun quantity(value: Double?, unit: String, digits: Int = 0, signed: Boolean = false): String {
        if (value == null) return "—"
        val threshold = when (digits) { 0 -> 0.5; 1 -> 0.05; else -> 0.005 }
        val number = if (kotlin.math.abs(value) < threshold) 0.0 else value
        val format = NumberFormat.getNumberInstance(german).apply { maximumFractionDigits = digits }
        return "${if (signed && number > 0) "+" else ""}${format.format(number)} $unit"
    }

    private fun time(minute: Int): String = if (minute in 0..1439) {
        String.format(german, "%02d:%02d Uhr", minute / 60, minute % 60)
    } else "Ohne Uhrzeit"

    private fun label(value: String, sp: Int, shade: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = sp.toFloat()
        setTextColor(shade)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun title(value: String, sp: Int) = label(value, sp, bold = true).apply {
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun button(text: String, click: () -> Unit, selected: Boolean = false, enabled: Boolean = true,
                       description: String? = null) = MaterialButton(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        minHeight = dp(48)
        minimumHeight = dp(48)
        cornerRadius = dp(14)
        backgroundTintList = ColorStateList.valueOf(if (selected) primary else tint)
        rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(if (selected) onPrimary else primary, 38))
        setTextColor(if (selected) onPrimary else if (enabled) ink else muted)
        isSelected = selected
        isEnabled = enabled
        if (description != null) contentDescription = description
        setOnClickListener { click() }
    }

    private fun buttonPair(buttons: List<View>) = row().apply {
        buttons.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (index > 0) marginStart = dp(8) })
        }
    }

    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun margins(top: Int, width: Int = MATCH_PARENT) = LinearLayout.LayoutParams(width, WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun color(attr: Int, fallback: Int) = MaterialColors.getColor(context, attr, fallback)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    private fun rounded(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }
}
