package com.example.mygarmincaloriestracker

import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Catalog screens. All methods and completion callbacks must run on the main thread.
 * Execute store work on an IO thread and invoke each completion exactly once with its result.
 * A false result means the item was already missing; failures leave the dialog open for retry.
 * Pass null for a list while loading; pass loadError for a failed load and wire onReload to retry.
 */
internal class TrackerCatalog(private val context: Context) {
    fun foods(
        items: List<Food>?,
        onUpdate: (Food, (Result<Boolean>) -> Unit) -> Unit,
        onDelete: (Long, (Result<Boolean>) -> Unit) -> Unit,
        onReload: () -> Unit,
        onAdd: (Food, (Result<Boolean>) -> Unit) -> Unit,
        loadError: Boolean = false,
    ): View {
        val screen = Screen()
        var current = items.orEmpty()
        fun render() {
            screen.clear()
            if (items == null) {
                screen.state(loadError, onReload)
                return
            }
            screen.note("Änderungen betreffen nur den Katalog. Bereits erfasste Mahlzeiten bleiben unverändert.")
            screen.addButton("Lebensmittel hinzufügen") {
                editFood(null, onAdd) { onReload() }
            }
            if (current.isEmpty()) screen.note("Keine Lebensmittel vorhanden.")
            current.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).forEach { food ->
                val portion = food.unitGrams?.let { " · ${food.unitName?.takeIf { name -> name.isNotBlank() } ?: "Portion"}: ${format(it)} g" }.orEmpty()
                screen.item(food.name, "${format(food.kcal100)} kcal / 100 g$portion", {
                    editFood(food, onUpdate) { saved ->
                        current = if (saved == null) current.filterNot { it.id == food.id }
                        else current.map { if (it.id == saved.id) saved else it }
                        render()
                        if (saved == null) screen.message("Das Lebensmittel ist nicht mehr vorhanden.")
                    }
                }, {
                    confirmDelete("Lebensmittel", food.name, { done -> onDelete(food.id, done) }) { deleted ->
                        current = current.filterNot { it.id == food.id }
                        render()
                        if (!deleted) screen.message("Das Lebensmittel ist nicht mehr vorhanden.")
                    }
                })
            }
        }
        render()
        return screen.view
    }

    fun sports(
        items: List<Sport>?,
        onUpdate: (Sport, (Result<Boolean>) -> Unit) -> Unit,
        onDelete: (Long, (Result<Boolean>) -> Unit) -> Unit,
        onReload: () -> Unit,
        onAdd: (Sport, (Result<Boolean>) -> Unit) -> Unit,
        loadError: Boolean = false,
    ): View {
        val screen = Screen()
        var current = items.orEmpty()
        fun render() {
            screen.clear()
            if (items == null) {
                screen.state(loadError, onReload)
                return
            }
            screen.note("Änderungen betreffen nur den Katalog. Bereits erfasste Aktivitäten bleiben unverändert.")
            screen.addButton("Aktivität hinzufügen") {
                editSport(null, onAdd) { onReload() }
            }
            if (current.isEmpty()) screen.note("Keine Aktivitäten vorhanden.")
            current.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).forEach { sport ->
                screen.item(sport.name, "MET: ${format(sport.light)} leicht · ${format(sport.medium)} mittel · ${format(sport.hard)} intensiv", {
                    editSport(sport, onUpdate) { saved ->
                        current = if (saved == null) current.filterNot { it.id == sport.id }
                        else current.map { if (it.id == saved.id) saved else it }
                        render()
                        if (saved == null) screen.message("Die Aktivität ist nicht mehr vorhanden.")
                    }
                }, {
                    confirmDelete("Aktivität", sport.name, { done -> onDelete(sport.id, done) }) { deleted ->
                        current = current.filterNot { it.id == sport.id }
                        render()
                        if (!deleted) screen.message("Die Aktivität ist nicht mehr vorhanden.")
                    }
                })
            }
        }
        render()
        return screen.view
    }

    private fun editFood(food: Food?, save: (Food, (Result<Boolean>) -> Unit) -> Unit, done: (Food?) -> Unit) {
        val form = Form()
        val name = form.input("Name des Lebensmittels", food?.name.orEmpty())
        val kcal = form.input("Energie (kcal pro 100 g)", food?.kcal100?.let(::inputNumber).orEmpty(), true)
        form.note("Portionsgewicht optional; ein Portionsname benötigt ein Gewicht.")
        val unit = form.input("Portionsname (optional)", food?.unitName.orEmpty())
        val grams = form.input("Gewicht pro Portion (g, optional)", food?.unitGrams?.let(::inputNumber).orEmpty(), true)
        form.show(if (food == null) "Lebensmittel hinzufügen" else "Lebensmittel bearbeiten") { submit ->
            if (name.value.isBlank()) return@show name.invalid("Gib dem Lebensmittel einen Namen.")
            val energy = kcal.number(true) ?: return@show kcal.invalid("Gib eine Zahl ab 0 ein.")
            val weight = grams.number()
            if (weight == null && (grams.value.isNotEmpty() || unit.value.isNotEmpty())) {
                return@show grams.invalid("Gib ein Portionsgewicht größer als 0 ein.")
            }
            if (weight != null && !(energy * (weight / 100.0)).isFinite()) {
                return@show grams.invalid("Die Energie pro Portion ist zu groß.")
            }
            val updated = Food(food?.id ?: 0L, name.value, energy, unit.value.ifBlank { null }, weight)
            submit({ completion -> save(updated, completion) }) { found -> done(if (found) updated else null) }
        }
    }

    private fun editSport(sport: Sport?, save: (Sport, (Result<Boolean>) -> Unit) -> Unit, done: (Sport?) -> Unit) {
        val form = Form()
        val name = form.input("Name der Aktivität", sport?.name.orEmpty())
        form.note("Gib für jede Intensität den Brutto-MET-Wert ein.")
        val light = form.input("Leicht (MET)", sport?.light?.let(::inputNumber).orEmpty(), true)
        val medium = form.input("Mittel (MET)", sport?.medium?.let(::inputNumber).orEmpty(), true)
        val hard = form.input("Intensiv (MET)", sport?.hard?.let(::inputNumber).orEmpty(), true)
        form.show(if (sport == null) "Aktivität hinzufügen" else "Aktivität bearbeiten") { submit ->
            if (name.value.isBlank()) return@show name.invalid("Gib der Aktivität einen Namen.")
            val message = "Gib einen MET-Wert größer als 0 ein."
            val l = light.number() ?: return@show light.invalid(message)
            val m = medium.number() ?: return@show medium.invalid(message)
            val h = hard.number() ?: return@show hard.invalid(message)
            val updated = Sport(sport?.id ?: 0L, name.value, l, m, h)
            submit({ completion -> save(updated, completion) }) { found -> done(if (found) updated else null) }
        }
    }

    private fun confirmDelete(kind: String, name: String, delete: ((Result<Boolean>) -> Unit) -> Unit, done: (Boolean) -> Unit) {
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val description = MaterialTextView(context).apply { text = "„$name“ aus dem Katalog löschen? Bereits erfasste Einträge bleiben unverändert." }
        val error = MaterialTextView(context).apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(description)
        content.addView(error)
        val dialog = MaterialAlertDialogBuilder(context).setTitle("$kind löschen?").setView(content)
            .setNegativeButton("Abbrechen", null).setPositiveButton("Löschen", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runOperation(dialog, error, delete, done)
        }
    }

    private fun runOperation(dialog: AlertDialog, error: MaterialTextView, operation: ((Result<Boolean>) -> Unit) -> Unit, done: (Boolean) -> Unit) {
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        positive.isEnabled = false
        negative.isEnabled = false
        dialog.setCancelable(false)
        error.visibility = View.GONE
        var completed = false
        val completion: (Result<Boolean>) -> Unit = completion@{ result ->
            if (completed) return@completion
            completed = true
            if (!dialog.isShowing) return@completion
            positive.isEnabled = true
            negative.isEnabled = true
            dialog.setCancelable(true)
            result.fold(onSuccess = { found -> dialog.dismiss(); done(found) }, onFailure = { failure ->
                error.text = if (failure is DuplicateCatalogNameException) "Dieser Name ist bereits im Katalog vorhanden."
                    else "Die Änderung konnte nicht gespeichert werden. Bitte versuche es erneut."
                error.visibility = View.VISIBLE
            })
        }
        try { operation(completion) } catch (e: Exception) { completion(Result.failure(e)) }
    }

    private inner class Screen {
        private val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        val view: View = ScrollView(context).apply { isFillViewport = true; addView(column, FrameLayout.LayoutParams(-1, -2)) }
        fun clear() = column.removeAllViews()
        fun addButton(label: String, action: () -> Unit) { column.addView(button(label, action)) }
        fun note(text: String) { column.addView(MaterialTextView(context).apply { this.text = text; setPadding(0, dp(8), 0, dp(8)) }) }
        fun message(text: String) {
            column.addView(MaterialTextView(context).apply { this.text = text; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }, 0)
        }
        fun state(error: Boolean, reload: () -> Unit) {
            note(if (error) "Der Katalog konnte nicht geladen werden." else "Katalog wird geladen …")
            if (error) column.addView(button("Erneut versuchen", reload))
        }
        fun item(name: String, detail: String, edit: () -> Unit, delete: () -> Unit) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(MaterialTextView(context).apply {
                    text = name
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1)
                }, LinearLayout.LayoutParams(-1, -2))
                addView(MaterialTextView(context).apply {
                    text = detail
                    setPadding(0, dp(8), 0, dp(8))
                }, LinearLayout.LayoutParams(-1, -2))
            }
            row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
            fun iconAction(icon: Int, description: String, action: () -> Unit) = ImageButton(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
                setBackgroundResource(android.R.drawable.list_selector_background)
                contentDescription = description
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { action() }
            }
            row.addView(iconAction(R.drawable.ic_edit_tracker, "$name bearbeiten", edit), LinearLayout.LayoutParams(dp(48), dp(48)))
            row.addView(iconAction(R.drawable.ic_delete_tracker, "$name löschen", delete), LinearLayout.LayoutParams(dp(48), dp(48)))
            column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        private fun button(label: String, action: () -> Unit) = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; setOnClickListener { action() }
        }
    }

    private inner class Form {
        private val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        private val error = MaterialTextView(context).apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        fun note(text: String) { column.addView(MaterialTextView(context).apply { this.text = text; setPadding(0, dp(8), 0, dp(8)) }) }
        fun input(label: String, initial: String, decimal: Boolean = false): Field {
            val box = TextInputLayout(context).apply { hint = label }
            val edit = TextInputEditText(box.context).apply {
                id = View.generateViewId()
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                if (decimal) {
                    keyListener = DigitsKeyListener.getInstance("0123456789,.-")
                    setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                }
                setText(initial)
            }
            box.addView(edit)
            column.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            return Field(box, edit)
        }
        fun show(title: String, validate: ((((Result<Boolean>) -> Unit) -> Unit, (Boolean) -> Unit) -> Unit) -> Unit) {
            column.addView(error)
            val scroll = ScrollView(context).apply { isFillViewport = true; addView(column, FrameLayout.LayoutParams(-1, -2)) }
            val dialog = MaterialAlertDialogBuilder(context).setTitle(title).setView(scroll)
                .setNegativeButton("Abbrechen", null).setPositiveButton("Speichern", null).create()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                validate { operation, done -> runOperation(dialog, error, operation, done) }
            }
        }
    }

    private class Field(private val box: TextInputLayout, private val edit: TextInputEditText) {
        val value: String get() = edit.text?.toString()?.trim().orEmpty()
        init { edit.doAfterTextChanged { box.error = null } }
        fun number(allowZero: Boolean = false): Double? = value.replace(',', '.').toDoubleOrNull()?.takeIf {
            it.isFinite() && (it > 0 || (allowZero && it == 0.0))
        }
        fun invalid(message: String) { box.error = message; edit.requestFocus() }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    private fun inputNumber(value: Double): String = if (value.isFinite()) BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', ',') else ""
    private fun format(value: Double): String = NumberFormat.getNumberInstance(Locale.GERMANY).apply { maximumFractionDigits = 2 }.format(value)
}
