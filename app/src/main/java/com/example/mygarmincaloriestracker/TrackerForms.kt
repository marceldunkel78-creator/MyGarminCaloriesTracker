package com.example.mygarmincaloriestracker

import android.content.Context
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

internal class TrackerForms(private val context: Context) {
    fun meal(foods: List<Food>, onSave: (Food, Double) -> Unit, onNew: () -> Unit) {
        val form = Form()
        var selected: Food? = null
        val pick = form.button("Lebensmittel auswählen")
        val selectionError = form.errorNote()
        val detail = form.note()
        val byPortion = form.checkbox("In Portionen eingeben")
        val grams = form.input("Menge (g)", Kind.DECIMAL)
        val portions = form.input("Anzahl der Portionen", Kind.DECIMAL)
        val preview = form.note()

        fun portionGrams(food: Food?): Double? = food?.unitGrams?.takeIf { it.isFinite() && it > 0.0 }
        fun portionName(food: Food?): String = food?.unitName?.takeIf { it.isNotBlank() } ?: "Portion"
        fun amountGrams(): Double? = if (byPortion.isChecked) {
            val count = portions.number()
            val perPortion = portionGrams(selected)
            if (count != null && perPortion != null) (count * perPortion).takeIf { it.isFinite() && it > 0.0 }
            else null
        } else grams.number()

        fun update() {
            val food = selected
            val inPortions = byPortion.isChecked
            grams.box.visibility = if (inPortions) View.GONE else View.VISIBLE
            portions.box.visibility = if (inPortions) View.VISIBLE else View.GONE
            val amount = amountGrams()
            val kcal = if (food != null && amount != null) mealKcal(food.kcal100, amount) else null
            preview.show(if (kcal != null && amount != null) {
                if (inPortions) "${format(amount)} g · ${format(kcal)} kcal" else "${format(kcal)} kcal"
            } else null)
        }
        pick.setOnClickListener {
            choose("Lebensmittel auswählen", "Lebensmittel suchen", foods, Food::name) { food ->
                selected = food
                pick.text = food.name
                selectionError.show(null)
                val unitWeight = portionGrams(food)
                val unit = portionName(food)
                byPortion.visibility = if (unitWeight == null) View.GONE else View.VISIBLE
                if (unitWeight == null) byPortion.isChecked = false
                val unitKcal = unitWeight?.let { mealKcal(food.kcal100, it) }
                val per100 = "${format(food.kcal100)} kcal pro 100 g"
                detail.show(if (unitWeight != null && unitKcal != null) {
                    "$per100 · ${format(unitKcal)} kcal pro $unit (${format(unitWeight)} g)"
                } else per100)
                update()
            }
        }
        byPortion.visibility = View.GONE
        byPortion.setOnCheckedChangeListener { _, _ -> update() }
        grams.onChange(::update)
        portions.onChange(::update)
        detail.show(null)
        update()

        form.dialog("Mahlzeit erfassen", "Neues Lebensmittel" to onNew) submit@{ dialog ->
            val food = selected ?: return@submit selectionError.report("Wähle ein Lebensmittel aus.")
            val field = if (byPortion.isChecked) portions else grams
            if (field.number() == null) return@submit field.invalid("Gib eine Menge größer als 0 ein.")
            val amount = amountGrams() ?: return@submit field.invalid("Die Menge lässt sich nicht in Gramm umrechnen.")
            if (mealKcal(food.kcal100, amount) == null) {
                return@submit field.invalid("Für diese Menge lässt sich kein gültiger Kalorienwert berechnen.")
            }
            dialog.dismiss()
            onSave(food, amount)
        }
    }

    fun editMealAmount(meal: Meal, onMealUpdated: () -> Unit, save: (Double, (Result<Boolean>) -> Unit) -> Unit) {
        val form = Form()
        form.note("${meal.name} · Die ursprünglich erfassten Kalorien pro Gramm bleiben erhalten.")
        val amount = form.input("Menge (g)", Kind.DECIMAL, initial = meal.grams.asInput())
        val preview = form.note()
        val error = form.errorNote()
        fun updatePreview() {
            val grams = amount.number()
            val kcal = grams?.let { rescaledMealCalories(meal, it) }
            preview.show(kcal?.let { "${format(it)} kcal" })
        }
        amount.onChange(::updatePreview)
        updatePreview()
        form.dialog("Mahlzeit bearbeiten") submit@{ dialog ->
            val grams = amount.number() ?: return@submit amount.invalid("Gib eine Menge größer als 0 ein.")
            if (rescaledMealCalories(meal, grams) == null) return@submit amount.invalid("Für diese Menge lässt sich kein gültiger Kalorienwert berechnen.")
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            positive.isEnabled = false
            negative.isEnabled = false
            dialog.setCancelable(false)
            error.show(null)
            save(grams) { result ->
                if (!dialog.isShowing) return@save
                positive.isEnabled = true
                negative.isEnabled = true
                dialog.setCancelable(true)
                result.fold(onSuccess = { updated ->
                    if (updated) {
                        dialog.dismiss()
                        onMealUpdated()
                    } else error.show("Der Eintrag wurde zwischenzeitlich geändert oder gelöscht. Bitte schließe den Dialog und versuche es erneut.")
                }, onFailure = { error.show("Die Änderung konnte nicht gespeichert werden. Bitte versuche es erneut.") })
            }
        }
    }

    fun food(onSave: (String, Double, String?, Double?) -> Unit) {
        val form = Form()
        val name = form.input("Name des Lebensmittels")
        val kcal = form.input("Energie (kcal pro 100 g)", Kind.DECIMAL)
        form.note("Für eine zusätzliche Portionsangabe kannst du das Gewicht pro Portion hinterlegen, z. B. 50 g für eine Scheibe.")
        val unit = form.input("Portionsname (optional)")
        val grams = form.input("Gewicht pro Portion (g, optional)", Kind.DECIMAL)
        val preview = form.note()
        fun update() {
            val per100 = kcal.number(allowZero = true)
            val weight = grams.number()
            val energy = if (per100 != null && weight != null) mealKcal(per100, weight) else null
            preview.show(energy?.let { "${format(it)} kcal pro ${unit.value.ifBlank { "Portion" }}" })
        }
        listOf(kcal, unit, grams).forEach { it.onChange(::update) }
        update()

        form.dialog("Lebensmittel anlegen") submit@{ dialog ->
            if (name.value.isBlank()) return@submit name.invalid("Gib dem Lebensmittel einen Namen.")
            val per100 = kcal.number(allowZero = true)
                ?: return@submit kcal.invalid("Gib eine Zahl ab 0 ein.")
            val weight = grams.number()
            if (weight == null && (grams.value.isNotEmpty() || unit.value.isNotEmpty())) {
                return@submit grams.invalid("Gib ein Portionsgewicht größer als 0 ein.")
            }
            if (weight != null && mealKcal(per100, weight) == null) {
                return@submit grams.invalid("Die Energie pro Portion ist zu groß.")
            }
            dialog.dismiss()
            onSave(name.value, per100, unit.value.ifBlank { null }, weight)
        }
    }

    fun activity(sports: List<Sport>, weight: Double?, onSave: (Sport, Int, Double) -> Unit, onNew: () -> Unit) {
        val form = Form()
        val validWeight = weight?.takeIf { it.isFinite() && it > 0.0 }
        var selected: Sport? = null
        val pick = form.button("Sportart auswählen")
        val selectionError = form.errorNote()
        val duration = form.input("Dauer (Minuten)", Kind.INTEGER)
        val manual = form.checkbox("Netto-kcal selbst eingeben")
        val energy = form.input("Zusätzlicher Verbrauch (Netto-kcal)", Kind.DECIMAL)
        val intensityLabel = form.note("Intensität", heading = true)
        val intensity = form.choices(listOf("Leicht", "Mittel", "Intensiv"), initial = 1)
        val guidance = form.note()
        val preview = form.note()
        val estimateError = form.errorNote()

        fun met(sport: Sport): Double = when (intensity.index) {
            0 -> sport.light
            2 -> sport.hard
            else -> sport.medium
        }
        fun estimate(sport: Sport, minutes: Int, kg: Double): Double? {
            val gross = met(sport).takeIf { it.isFinite() && it > 0.0 } ?: return null
            return TrackerMath.activityKcal(gross, kg, minutes).takeIf { it.isFinite() }
        }
        fun update() {
            val useManual = manual.isChecked
            energy.box.visibility = if (useManual) View.VISIBLE else View.GONE
            intensityLabel.visibility = if (useManual) View.GONE else View.VISIBLE
            intensity.group.visibility = if (useManual) View.GONE else View.VISIBLE
            guidance.text = when {
                useManual -> "Gib nur die zusätzlichen Kalorien ein, ohne den Ruheverbrauch während des Trainings."
                validWeight == null -> "Für die Schätzung fehlt ein Körpergewicht. Du kannst stattdessen Netto-kcal selbst eingeben."
                else -> "Die Schätzung verwendet ${format(validWeight)} kg Körpergewicht und zieht 1 MET für den Ruheverbrauch ab."
            }
            val sport = selected
            val minutes = duration.positiveInt()
            val kcal = if (!useManual && sport != null && minutes != null && validWeight != null) {
                estimate(sport, minutes, validWeight)
            } else null
            preview.show(if (kcal != null && sport != null) {
                "Geschätzt: ${format(kcal)} Netto-kcal · ${format(met(sport))} MET brutto"
            } else null)
            estimateError.show(null)
        }
        pick.setOnClickListener {
            choose("Sportart auswählen", "Sportart suchen", sports, Sport::name) { sport ->
                selected = sport
                pick.text = sport.name
                selectionError.show(null)
                update()
            }
        }
        manual.isChecked = validWeight == null
        manual.setOnCheckedChangeListener { _, _ -> update() }
        duration.onChange(::update)
        intensity.group.setOnCheckedChangeListener { _, _ -> update() }
        update()

        form.dialog("Aktivität erfassen", "Neue Sportart" to onNew) submit@{ dialog ->
            val sport = selected ?: return@submit selectionError.report("Wähle eine Sportart aus.")
            val minutes = duration.positiveInt()
                ?: return@submit duration.invalid("Gib eine ganze Minutenzahl größer als 0 ein.")
            val kcal = if (manual.isChecked) {
                energy.number(allowZero = true) ?: return@submit energy.invalid("Gib Netto-kcal ab 0 ein.")
            } else {
                val kg = validWeight ?: return@submit estimateError.report(
                    "Trage ein Körpergewicht ein oder gib Netto-kcal selbst ein.",
                )
                estimate(sport, minutes, kg) ?: return@submit estimateError.report(
                    "Mit diesen Angaben ist keine Schätzung möglich. Prüfe Dauer und Intensität oder gib Netto-kcal selbst ein.",
                )
            }
            dialog.dismiss()
            onSave(sport, minutes, kcal)
        }
    }

    fun sport(onSave: (String, Double, Double, Double) -> Unit) {
        val form = Form()
        val name = form.input("Name der Sportart")
        form.note("Gib für jede Intensität den Brutto-MET-Wert ein. 1 MET entspricht ungefähr dem Ruheverbrauch; dieser Anteil wird bei der Schätzung abgezogen.")
        val light = form.input("Leicht (MET)", Kind.DECIMAL)
        val medium = form.input("Mittel (MET)", Kind.DECIMAL)
        val hard = form.input("Intensiv (MET)", Kind.DECIMAL)
        form.dialog("Sportart anlegen") submit@{ dialog ->
            if (name.value.isBlank()) return@submit name.invalid("Gib der Sportart einen Namen.")
            val message = "Gib einen MET-Wert größer als 0 ein."
            val lightMet = light.number() ?: return@submit light.invalid(message)
            val mediumMet = medium.number() ?: return@submit medium.invalid(message)
            val hardMet = hard.number() ?: return@submit hard.invalid(message)
            dialog.dismiss()
            onSave(name.value, lightMet, mediumMet, hardMet)
        }
    }

    @RequiresApi(26)
    fun body(existing: Body?, day: LocalDate, onSave: (Body) -> Unit) {
        val form = Form()
        val date = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.GERMANY))
        form.note("$date · Erfasse nur die Werte, die du gemessen hast.")
        val weight = form.input("Gewicht (kg)", Kind.DECIMAL, initial = existing?.weight.asInput())
        val waist = form.input("Taillenumfang (cm)", Kind.DECIMAL, initial = existing?.waist.asInput())
        val hip = form.input("Hüftumfang (cm)", Kind.DECIMAL, initial = existing?.hip.asInput())
        val fields = listOf(weight, waist, hip)
        form.dialog("Körpermaße erfassen") submit@{ dialog ->
            if (fields.all { it.value.isEmpty() }) return@submit weight.invalid("Gib mindestens einen Messwert ein.")
            fields.firstOrNull { it.value.isNotEmpty() && it.number() == null }?.let {
                return@submit it.invalid("Gib einen Wert größer als 0 ein oder lass das Feld leer.")
            }
            dialog.dismiss()
            onSave(Body(day, weight.number(), waist.number(), hip.number()))
        }
    }

    fun settings(
        profile: Profile,
        garminAvailable: Boolean,
        onSave: (Profile) -> Unit,
        onPermissions: () -> Unit,
        onSync: () -> Unit,
        onClear: () -> Unit,
    ): View {
        val form = Form()
        form.note("Kalorienverbrauch", heading = true)
        form.note("Ohne nutzbaren Garmin-Wert schätzt die App den Ruheverbrauch nach Mifflin-St Jeor. Dafür braucht sie Alter, Größe, Formelvariante und Gewicht. Für einen eher sitzenden Alltag wird der Ruheverbrauch mit 1,2 multipliziert; separat erfasste Aktivitäten kommen netto hinzu.")
        val age = form.input("Alter (Jahre, optional)", Kind.INTEGER, profile.age?.toString().orEmpty())
        val height = form.input("Körpergröße (cm, optional)", Kind.DECIMAL, profile.height.asInput())
        form.note("Mifflin-St Jeor: Formelvariante", heading = true)
        val offsets = listOf(null, 5, -161)
        val offset = form.choices(
            listOf("Keine Auswahl", "Männliche Formel (+5)", "Weibliche Formel (−161)"),
            initial = offsets.indexOf(profile.formulaOffset).coerceAtLeast(0),
        )
        form.note("Eine manuelle Tagesbasis ersetzt den geschätzten Ruheverbrauch. Sie kann deinen Alltagsverbrauch enthalten, aber keine separat erfassten Workouts.")
        val baseline = form.input("Manuelle Tagesbasis (kcal, optional)", Kind.DECIMAL, profile.manual.asInput())
        val garmin = SwitchMaterial(context).apply {
            text = "Garmin-Tageswert bevorzugen"
            isChecked = profile.useGarmin
            minHeight = dp(48)
        }
        form.add(garmin)
        val garminRestOnly = SwitchMaterial(context).apply {
            text = "Garmin-Gesamtwert enthält nur Ruhekalorien"
            isChecked = profile.garminTotalIsResting
            minHeight = dp(48)
        }
        form.add(garminRestOnly)
        form.note("Nur einschalten, wenn der importierte Gesamtwert den Ruhekalorien in Garmin Connect entspricht. Dann addiert die App die separat importierten Aktivkalorien. Für echte Gesamtwerte ausgeschaltet lassen.")
        form.add(MaterialButton(context).apply {
            text = "Einstellungen speichern"
            setOnClickListener save@{
                val years = age.positiveInt()
                if (age.value.isNotEmpty() && years == null) {
                    return@save age.invalid("Gib ganze Jahre größer als 0 ein oder lass das Feld leer.")
                }
                val size = height.number()
                if (height.value.isNotEmpty() && size == null) {
                    return@save height.invalid("Gib eine Größe größer als 0 ein oder lass das Feld leer.")
                }
                val manual = baseline.number()
                if (baseline.value.isNotEmpty() && manual == null) {
                    return@save baseline.invalid("Gib einen Wert größer als 0 ein oder lass das Feld leer.")
                }
                onSave(Profile(years, size, offsets.getOrNull(offset.index), manual, garmin.isChecked, garminRestOnly.isChecked))
            }
        })

        form.note("Garmin & Health Connect", heading = true)
        form.note("Kein API-Schlüssel nötig. Ab Android 14 kannst du in Garmin Connect die Freigabe an Health Connect aktivieren. Welche Tageswerte und Aktivitäten importiert werden können, hängt davon ab, welche Daten Garmin Connect bereitstellt.")
        form.note("Wenn ein Garmin-Gesamtwert verwendet wird, rechnet die App keine separat erfassten Workouts hinzu.")
        if (!garminAvailable) form.note("Health Connect ist für den Garmin-Import auf diesem Gerät derzeit nicht verfügbar.")
        form.note("Erlaube der App den Lesezugriff in Health Connect. Synchronisiere den gewünschten Tag zuerst in Garmin Connect und importiere dann den im Tracker ausgewählten Tag. Diese Aktionen speichern keine Einstellungen.")
        form.button("Health-Connect-Zugriff verwalten", onPermissions).isEnabled = garminAvailable
        form.button("Ausgewählten Tag importieren", onSync).isEnabled = garminAvailable
        form.button("Importierte Garmin-Daten löschen") {
            MaterialAlertDialogBuilder(context)
                .setTitle("Garmin-Importe löschen?")
                .setMessage("Alle Garmin-Importe werden aus dem Tracker gelöscht. Manuelle Einträge sowie Daten in Garmin Connect und Health Connect bleiben erhalten.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Löschen") { _, _ -> onClear() }
                .show()
        }
        return form.scroll()
    }

    private fun <T> choose(
        title: String,
        hint: String,
        items: List<T>,
        label: (T) -> String,
        onChoose: (T) -> Unit,
    ) {
        val header = Form()
        header.note(title, heading = true)
        val search = header.input(hint, Kind.SEARCH)
        val matches = header.note()
        val adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_1)
        var visible: List<T> = emptyList()
        fun filter() {
            visible = items.filter { label(it).contains(search.value, ignoreCase = true) }
            adapter.setNotifyOnChange(false)
            adapter.clear()
            adapter.addAll(visible.map(label))
            adapter.notifyDataSetChanged()
            matches.show(when {
                items.isEmpty() -> "Der Katalog ist noch leer."
                visible.isEmpty() -> "Keine passenden Einträge gefunden."
                else -> null
            })
        }
        filter()
        search.onChange(::filter)
        MaterialAlertDialogBuilder(context)
            .setCustomTitle(header.column)
            .setAdapter(adapter) { _, position -> visible.getOrNull(position)?.let(onChoose) }
            .setNegativeButton("Abbrechen", null)
            .create().apply {
                showWithResize()
                // AlertController only checks the custom content panel, not the custom title, for editors.
                window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            }
    }

    private inner class Form {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }

        fun add(view: View) {
            column.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        fun note(message: String = "", heading: Boolean = false): TextView = MaterialTextView(context).apply {
            setTextAppearance(if (heading) com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1
                else com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
            if (heading) {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            }
            text = message
            add(this)
        }

        fun errorNote(): TextView = note().apply {
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorError))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }

        fun button(label: String, action: (() -> Unit)? = null): MaterialButton = MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = label
            if (action != null) setOnClickListener { action() }
            add(this)
        }

        fun checkbox(label: String): MaterialCheckBox = MaterialCheckBox(context).apply {
            text = label
            minHeight = dp(48)
            add(this)
        }

        fun input(label: String, kind: Kind = Kind.TEXT, initial: String = ""): Input {
            val box = TextInputLayout(context).apply { hint = label }
            val edit = TextInputEditText(box.context).apply {
                id = View.generateViewId()
                setSingleLine(true)
                inputType = when (kind) {
                    Kind.TEXT -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    Kind.SEARCH -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    Kind.INTEGER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                    Kind.DECIMAL -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
                if (kind == Kind.DECIMAL) {
                    // Accept decimal commas and points independently of the device keyboard locale.
                    keyListener = DigitsKeyListener.getInstance("0123456789,.-")
                    setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                }
                setText(initial)
            }
            box.addView(edit)
            add(box)
            return Input(box, edit)
        }

        fun choices(labels: List<String>, initial: Int): Choice {
            val group = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
            val ids = labels.map { label ->
                val button = MaterialRadioButton(context).apply {
                    id = View.generateViewId()
                    text = label
                    minHeight = dp(48)
                }
                group.addView(button, RadioGroup.LayoutParams(-1, -2))
                button.id
            }
            group.check(ids[initial])
            add(group)
            return Choice(group, ids)
        }

        fun scroll(): ScrollView = ScrollView(context).apply {
            isFillViewport = true
            addView(column, FrameLayout.LayoutParams(-1, -2))
        }

        fun dialog(title: String, extra: Pair<String, () -> Unit>? = null, onSubmit: (AlertDialog) -> Unit) {
            val builder = MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(scroll())
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Speichern", null)
            extra?.let { (label, action) ->
                builder.setNeutralButton(label) { dialog, _ ->
                    dialog.dismiss()
                    action()
                }
            }
            val dialog = builder.create()
            dialog.showWithResize()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onSubmit(dialog) }
        }
    }

    private class Input(val box: TextInputLayout, private val edit: TextInputEditText) {
        val value: String get() = edit.text?.toString()?.trim().orEmpty()

        init {
            onChange { if (box.error != null) box.error = null }
        }

        fun number(allowZero: Boolean = false): Double? = value.replace(',', '.').toDoubleOrNull()?.takeIf {
            it.isFinite() && (it > 0.0 || (allowZero && it == 0.0))
        }

        fun positiveInt(): Int? = value.toIntOrNull()?.takeIf { it > 0 }

        fun onChange(action: () -> Unit) {
            edit.doAfterTextChanged { action() }
        }

        fun invalid(message: String) {
            box.error = message
            edit.requestFocus()
            box.reveal()
        }
    }

    private class Choice(val group: RadioGroup, private val ids: List<Int>) {
        val index: Int get() = ids.indexOf(group.checkedRadioButtonId)
    }

    private enum class Kind { TEXT, SEARCH, INTEGER, DECIMAL }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    private fun format(value: Double): String = NumberFormat.getNumberInstance(Locale.GERMANY).apply {
        maximumFractionDigits = 2
    }.format(value)

    private fun Double?.asInput(): String = this?.takeIf { it.isFinite() }?.let {
        BigDecimal.valueOf(it).stripTrailingZeros().toPlainString().replace('.', ',')
    }.orEmpty()

    private fun mealKcal(per100: Double, grams: Double): Double? =
        (per100 * (grams / 100.0)).takeIf { it.isFinite() && it >= 0.0 }

    private fun AlertDialog.showWithResize() {
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        show()
    }
}

private fun TextView.show(message: String?) {
    text = message.orEmpty()
    visibility = if (message == null) View.GONE else View.VISIBLE
}

private fun TextView.report(message: String) {
    show(message)
    reveal()
}

private fun View.reveal() {
    post { requestRectangleOnScreen(android.graphics.Rect(0, 0, width, height)) }
}
