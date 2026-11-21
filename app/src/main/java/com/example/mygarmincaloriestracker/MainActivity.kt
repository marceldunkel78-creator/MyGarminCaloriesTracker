package com.example.mygarmincaloriestracker

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textview.MaterialTextView
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private enum class Page { DAY, STATISTICS, SETTINGS, FOODS, SPORTS }

    private val store by lazy { TrackerStore(applicationContext) }
    private val garmin by lazy { GarminHealth(applicationContext) }
    private val forms by lazy { TrackerForms(this) }
    private val views by lazy { TrackerViews(this) }
    private val catalog by lazy { TrackerCatalog(this) }
    private var refreshLayout: SwipeRefreshLayout? = null
    private var startX = 0f
    private var startY = 0f
    private var swipeEligible = false
    private var syncFromRefresh = false
    private lateinit var toolbar: MaterialToolbar
    private lateinit var host: FrameLayout
    private lateinit var add: FloatingActionButton
    private var page = Page.DAY
    private var day = LocalDate.now()
    private var anchor = day
    private var period = StatsPeriod.WEEK
    private var renderJob: Job? = null
    private var syncJob: Job? = null
    private var saving = false
    private var syncAfterPermission = false

    private val healthPermissions = registerForActivityResult(GarminHealth.permissionContract()) { granted ->
        val import = syncAfterPermission
        syncAfterPermission = false
        if (granted.isEmpty()) {
            toast("Kein Lesezugriff erteilt. Du kannst ihn in Health Connect ändern.")
        } else if (import) {
            sync(manual = true)
        } else {
            toast("Health-Connect-Zugriff aktualisiert. Ziehe die Übersicht zum Aktualisieren nach unten.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        day = savedInstanceState?.getLong("day")?.let(LocalDate::ofEpochDay) ?: LocalDate.now()
        anchor = savedInstanceState?.getLong("anchor")?.let(LocalDate::ofEpochDay) ?: day
        page = savedInstanceState?.getString("page")?.let(Page::valueOf) ?: Page.DAY
        period = savedInstanceState?.getString("period")?.let(StatsPeriod::valueOf) ?: StatsPeriod.WEEK
        syncAfterPermission = savedInstanceState?.getBoolean("syncAfterPermission") ?: false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
        }
        toolbar = MaterialToolbar(this).apply { minimumHeight = dp(56) }
        host = FrameLayout(this)
        add = FloatingActionButton(this).apply {
            size = FloatingActionButton.SIZE_NORMAL
            shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(28f * resources.displayMetrics.density).build()
            setImageResource(R.drawable.ic_add_tracker)
            contentDescription = "Eintrag hinzufügen"
            setOnClickListener { addEntry() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        root.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        host.addView(add, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(20)
            bottomMargin = dp(20)
        })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != Page.DAY) open(Page.DAY) else finish()
            }
        })
        open(page)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("day", day.toEpochDay())
        outState.putLong("anchor", anchor.toEpochDay())
        outState.putString("page", page.name)
        outState.putString("period", period.name)
        outState.putBoolean("syncAfterPermission", syncAfterPermission)
        super.onSaveInstanceState(outState)
    }

    private fun open(next: Page, resetAnchor: Boolean = false) {
        page = next
        if (resetAnchor) anchor = day
        toolbar.title = when (page) {
            Page.DAY -> "MyGarmin Calorie Tracker"
            Page.STATISTICS -> "Statistik"
            Page.SETTINGS -> "Einstellungen"
            Page.FOODS -> "Lebensmittel-Datenbank"
            Page.SPORTS -> "Aktivitäten-Datenbank"
        }
        toolbar.menu.clear()
        toolbar.navigationIcon = if (page == Page.DAY) null else getDrawable(R.drawable.ic_back_tracker)
        toolbar.navigationContentDescription = "Zur Tagesübersicht"
        toolbar.setNavigationOnClickListener { open(Page.DAY) }
        if (page == Page.DAY) {
            toolbar.menu.add("Tag im Kalender wählen").apply {
                setIcon(R.drawable.ic_calendar_tracker)
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener { chooseDay(); true }
            }
            toolbar.menu.add("Statistik").setOnMenuItemClickListener { open(Page.STATISTICS, true); true }
            toolbar.menu.add("Lebensmittel-Datenbank").setOnMenuItemClickListener { open(Page.FOODS); true }
            toolbar.menu.add("Aktivitäten-Datenbank").setOnMenuItemClickListener { open(Page.SPORTS); true }
            toolbar.menu.add("Vorheriger Tag").setOnMenuItemClickListener { changeDay(-1); true }
            toolbar.menu.add("Nächster Tag").setOnMenuItemClickListener { changeDay(1); true }
            toolbar.menu.add("Einstellungen").setOnMenuItemClickListener { open(Page.SETTINGS); true }
            toolbar.menu.add("Garmin aktualisieren").setOnMenuItemClickListener { sync(manual = true); true }
        }
        add.visibility = if (page == Page.DAY) View.VISIBLE else View.GONE
        render()
    }

    private fun render(preserveScroll: Boolean = false) {
        renderJob?.cancel()
        val requested = page
        val date = day
        val selectedPeriod = period
        val statsDay = anchor
        val currentContent = host.children.firstOrNull { it !== add }
        val currentScroll = if (currentContent is SwipeRefreshLayout) currentContent.getChildAt(0) as? ScrollView else currentContent as? ScrollView
        val scrollY = if (preserveScroll) currentScroll?.scrollY ?: 0 else 0
        if (!preserveScroll) replaceContent(ProgressBar(this), centered = true)
        renderJob = lifecycleScope.launch {
            try {
                val now = LocalDateTime.now()
                val view = when (requested) {
                    Page.DAY -> {
                        val (data, measurements, streak) = withContext(Dispatchers.IO) {
                            Triple(store.day(date), store.latestMeasurements(date), store.deficitStreak(date, now))
                        }
                        val content = views.dashboard(data, now,
                            onDeleteMeal = { id -> confirmDelete("Mahlzeit") { store.deleteMeal(id) } },
                            onDeleteActivity = { id -> confirmDelete("Aktivität") { store.deleteActivity(id) } },
                            onSettings = { open(Page.SETTINGS) }, streak = streak, measurements = measurements,
                            onEditMeal = { meal ->
                                forms.editMealAmount(meal, onMealUpdated = { render(preserveScroll = true) }) { grams, done ->
                                    catalogWrite(done) { store.updateMealAmount(meal, grams) }
                                }
                            })
                        SwipeRefreshLayout(this@MainActivity).apply {
                            addView(content)
                            setOnRefreshListener { sync(manual = true, fromRefresh = true) }
                            isRefreshing = syncFromRefresh
                        }
                    }
                    Page.STATISTICS -> {
                        val (from, to) = selectedPeriod.range(statsDay)
                        val data = withContext(Dispatchers.IO) { store.days(from, minOf(to, now.toLocalDate())) }
                        views.statistics(data, now, selectedPeriod, statsDay, onPeriod = {
                            period = it
                            render()
                        }, onStep = { step ->
                            val next = selectedPeriod.move(statsDay, step)
                            if (selectedPeriod.range(next).first <= LocalDate.now()) {
                                anchor = minOf(next, LocalDate.now())
                                render()
                            }
                        })
                    }
                    Page.FOODS -> {
                        val items = withContext(Dispatchers.IO) { store.foods() }
                        catalog.foods(items,
                            onUpdate = { item, done -> catalogWrite(done) { store.updateFood(item) } },
                            onDelete = { id, done -> catalogWrite(done) { store.deleteFood(id) } },
                            onReload = { render() },
                            onAdd = { food, done -> catalogWrite(done) {
                                store.addFood(food.name, food.kcal100, food.unitName, food.unitGrams)
                                true
                            } })
                    }
                    Page.SPORTS -> {
                        val items = withContext(Dispatchers.IO) { store.sports() }
                        catalog.sports(items,
                            onUpdate = { item, done -> catalogWrite(done) { store.updateSport(item) } },
                            onDelete = { id, done -> catalogWrite(done) { store.deleteSport(id) } },
                            onReload = { render() },
                            onAdd = { sport, done -> catalogWrite(done) {
                                store.addSport(sport.name, sport.light, sport.medium, sport.hard)
                                true
                            } })
                    }
                    Page.SETTINGS -> {
                        val profile = withContext(Dispatchers.IO) { store.profile() }
                        forms.settings(profile, garmin.available(),
                            onSave = { value ->
                                change("Einstellungen gespeichert.", then = { open(Page.DAY) }) {
                                    store.saveProfile(value)
                                }
                            },
                            onPermissions = { requestPermissionsForGarmin() },
                            onSync = { sync(manual = true) },
                            onClear = {
                                syncJob?.cancel()
                                change("Garmin-Importe gelöscht. Automatischer Import ist aus.") {
                                    store.saveProfile(store.profile().copy(useGarmin = false))
                                    store.clearGarmin()
                                }
                            })
                    }
                }
                replaceContent(view)
                val scroll = if (view is SwipeRefreshLayout) view.getChildAt(0) else view
                if (scrollY > 0) scroll.post { scroll.scrollTo(0, scrollY) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                replaceContent(MaterialTextView(this@MainActivity).apply {
                    text = "Daten konnten nicht geladen werden. Zum Wiederholen tippen."
                    gravity = Gravity.CENTER
                    setPadding(dp(20), dp(20), dp(20), dp(20))
                    setOnClickListener { render() }
                })
            }
        }
    }

    private fun replaceContent(view: View, centered: Boolean = false) {
        host.children.toList().filter { it !== add }.forEach { host.removeView(it) }
        refreshLayout = view as? SwipeRefreshLayout
        host.addView(view, 0, if (centered) FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
            else FrameLayout.LayoutParams(-1, -1))
    }

    private fun changeDay(delta: Long) {
        val candidate = day.plusDays(delta)
        if (candidate <= LocalDate.now()) {
            day = candidate
            open(Page.DAY)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (page != Page.DAY) {
            swipeEligible = false
            return super.dispatchTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y; swipeEligible = true }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> swipeEligible = false
            MotionEvent.ACTION_UP -> {
                val dx = event.x - startX
                val dy = event.y - startY
                val threshold = maxOf(dp(72), ViewConfiguration.get(this).scaledTouchSlop * 4)
                if (swipeEligible && kotlin.math.abs(dx) > threshold && kotlin.math.abs(dx) > 1.5f * kotlin.math.abs(dy)) {
                    swipeEligible = false
                    val cancelled = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancelled)
                    cancelled.recycle()
                    changeDay(if (dx < 0) 1 else -1)
                    return true
                }
                swipeEligible = false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun catalogWrite(done: (Result<Boolean>) -> Unit, operation: () -> Boolean) {
        lifecycleScope.launch {
            val result = try { Result.success(withContext(Dispatchers.IO) { operation() }) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
            done(result)
        }
    }

    private fun chooseDay() {
        DatePickerDialog(this, { _, year, month, date ->
            day = LocalDate.of(year, month + 1, date)
            open(Page.DAY)
        }, day.year, day.monthValue - 1, day.dayOfMonth).apply {
            datePicker.maxDate = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.show()
    }

    private fun addEntry() {
        if (saving) return
        val date = day
        MaterialAlertDialogBuilder(this)
            .setTitle("Eintrag hinzufügen")
            .setItems(arrayOf("Mahlzeit", "Aktivität", "Körpermaße")) { _, which ->
                when (which) {
                    0 -> meal(date)
                    1 -> activity(date)
                    2 -> load({ store.day(date).body }) { existing ->
                        forms.body(existing, date) { body -> change("Körpermaße gespeichert.") { store.saveBody(body) } }
                    }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun meal(date: LocalDate): Unit = load({ store.foods() }) { foods ->
        forms.meal(foods, onSave = { food, grams ->
            entryTime(date) { minute ->
                change("Mahlzeit gespeichert.") {
                    store.addMeal(date, food.name, grams, food.kcal100 * grams / 100, minute)
                }
            }
        }, onNew = {
            forms.food { name, kcal, unit, grams ->
                change("Lebensmittel hinzugefügt.", then = { meal(date) }) { store.addFood(name, kcal, unit, grams) }
            }
        })
    }

    private fun activity(date: LocalDate): Unit = load({ store.sports() to store.day(date).latestBody?.weight }) { (sports, weight) ->
        forms.activity(sports, weight, onSave = { sport, minutes, kcal ->
            entryTime(date) { minute ->
                change("Aktivität gespeichert.") { store.addActivity(date, sport.name, minutes, kcal, minute) }
            }
        }, onNew = {
            forms.sport { name, light, medium, hard ->
                change("Aktivität zum Katalog hinzugefügt.", then = { activity(date) }) {
                    store.addSport(name, light, medium, hard)
                }
            }
        })
    }

    private fun entryTime(date: LocalDate, onTime: (Int) -> Unit) {
        val now = LocalDateTime.now()
        if (date == now.toLocalDate()) {
            onTime(now.hour * 60 + now.minute)
        } else {
            TimePickerDialog(this, { _, hours, minutes -> onTime(hours * 60 + minutes) },
                12, 0, true).apply { setTitle("Uhrzeit des Eintrags") }.show()
        }
    }

    private fun confirmDelete(kind: String, write: () -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle("$kind löschen?")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Löschen") { _, _ -> change("Eintrag gelöscht.", write = write) }.show()
    }

    private fun <T> load(read: () -> T, show: (T) -> Unit) {
        lifecycleScope.launch {
            try { show(withContext(Dispatchers.IO) { read() }) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { toast("Daten konnten nicht geladen werden.") }
        }
    }

    private fun change(message: String, then: (() -> Unit)? = null, write: () -> Unit) {
        if (saving) return
        saving = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { write() }
                saving = false
                toast(message)
                if (then != null) then() else render(preserveScroll = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                toast("Die Änderung konnte nicht gespeichert werden.")
            } finally {
                saving = false
            }
        }
    }

    private fun requestPermissionsForGarmin(importAfter: Boolean = false) {
        if (!garmin.available()) {
            toast("Garmin-Import über Health Connect braucht Android 14 oder neuer.")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Garmin-Daten lesen")
            .setMessage("Die App liest nur von Garmin freigegebene Kalorien und Trainings aus Health Connect. Importierte Daten bleiben lokal auf diesem Gerät.")
            .setNegativeButton("Abbrechen", null)
            .setNeutralButton("Health Connect öffnen") { _, _ ->
                if (Build.VERSION.SDK_INT >= 34) {
                    try { startActivity(Intent(HEALTH_SETTINGS_ACTION)) }
                    catch (_: ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }
            }
            .setPositiveButton("Zugriff auswählen") { _, _ ->
                syncAfterPermission = importAfter
                try { healthPermissions.launch(GarminHealth.permissions) }
                catch (_: ActivityNotFoundException) { toast("Health Connect konnte nicht geöffnet werden.") }
            }.show()
    }

    private fun sync(manual: Boolean = false, fromRefresh: Boolean = false) {
        fun stopRefresh() { if (fromRefresh) refreshLayout?.isRefreshing = false }
        if (!garmin.available()) {
            if (manual) toast("Garmin-Import über Health Connect braucht Android 14 oder neuer.")
            stopRefresh()
            return
        }
        if (syncJob?.isActive == true || saving) {
            if (manual) toast("Bitte warte, bis der aktuelle Vorgang abgeschlossen ist.")
            stopRefresh()
            return
        }
        val date = day
        syncFromRefresh = fromRefresh
        syncJob = lifecycleScope.launch {
            try {
                val enabled = withContext(Dispatchers.IO) { store.profile().useGarmin }
                if (!manual && !enabled) return@launch
                if (garmin.granted().isEmpty()) {
                    if (manual) requestPermissionsForGarmin(importAfter = true)
                    return@launch
                }
                if (manual && !fromRefresh) toast("Garmin-Daten werden gelesen …")
                val result = garmin.read(date, includeDetails = manual)
                val data = result.data
                val hasData = data.total != null || data.active != null || data.activities.isNotEmpty()
                if (hasData) withContext(Dispatchers.IO) {
                    val previous = store.day(date).garmin
                    if (data.total != null || previous?.total == null) {
                        store.saveGarmin(date, data)
                    } else {
                        // A partial result (sync delay or permission change) must not erase a usable saved total.
                        store.saveGarmin(date, previous.copy(activities = data.activities.ifEmpty { previous.activities }))
                    }
                }
                if (page == Page.DAY && day == date && hasData) render(preserveScroll = true)
                if (fromRefresh) {
                    toast(if (!hasData) "Keine Garmin-Daten für diesen Tag gefunden." else if (data.total == null) "Aktivitäten importiert; kein vollständiger Garmin-Verbrauch verfügbar." else "Garmin-Daten aktualisiert.")
                } else if (manual) {
                    val displayDate = date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY))
                    val format = java.text.NumberFormat.getNumberInstance(Locale.GERMANY).apply { maximumFractionDigits = 1 }
                    fun kcal(value: Double?) = value?.let { "${format.format(it)} kcal" } ?: "nicht verfügbar"
                    val message = buildString {
                        append("Garmin-Werte aus Health Connect:\n\n")
                        append("Verwendbarer Gesamtwert: ${kcal(data.total)}\n")
                        append("Aktivkalorien: ${kcal(data.active)}\n")
                        append("Aktivitäten: ${data.activities.size}")
                        if (data.total == null) append("\n\nKein vollständiger Kalorienwert verfügbar. Einzelne Aktivitätswerte sind kein Tagesverbrauch.")
                    }
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Garmin-Import · $displayDate")
                        .setMessage(message)
                        .setNeutralButton("Zeitbereiche") { _, _ ->
                            MaterialAlertDialogBuilder(this@MainActivity)
                                .setTitle("Health-Connect-Daten · $displayDate")
                                .setMessage(result.details.ifBlank { "Keine Zeitbereiche verfügbar." })
                                .setPositiveButton("OK", null)
                                .show()
                        }
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: SecurityException) {
                if (manual) toast("Kein Zugriff auf diesen Tag. Prüfe die Berechtigungen und den Health-Connect-Verlauf.")
            } catch (_: Exception) {
                if (manual) toast("Garmin-Daten konnten nicht gelesen werden.")
            } finally {
                syncFromRefresh = false
                stopRefresh()
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val HEALTH_SETTINGS_ACTION = "android.health.connect.action.HEALTH_CONNECT_SETTINGS"
    }
}

/** The external Health Connect alias opens this read-only screen; it handles no input Intents. */
class HealthPrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val spacing = (24 * resources.displayMetrics.density).toInt()
        setContentView(ScrollView(this).apply {
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            addView(MaterialTextView(this@HealthPrivacyActivity).apply {
                setPadding(spacing, spacing * 2, spacing, spacing * 2)
                textSize = 17f
                text = "Datenschutz · Kalorien & Maße\n\n" +
                    "Mit deiner Freigabe liest die App Garmin-Trainings sowie Gesamt- und Aktivitätskalorien aus Health Connect. Sie nutzt diese Werte für dein Protokoll und deine Kalorienbilanz. Sie schreibt keine Daten nach Health Connect und sendet keine Daten an einen Server.\n\n" +
                    "Manuelle Einträge, Einstellungen und importierte Werte werden lokal gespeichert. Den Lesezugriff kannst du in Health Connect widerrufen. In den App-Einstellungen kannst du bisher importierte Garmin-Daten löschen."
            })
        })
    }
}
