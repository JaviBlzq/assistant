package com.javiblzq.assistant.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.javiblzq.assistant.data.AssistantConfig
import com.javiblzq.assistant.data.CommuteRoute
import com.javiblzq.assistant.data.DashboardSnapshot
import com.javiblzq.assistant.data.MarketQuoteItem
import com.javiblzq.assistant.data.ServiceHealthItem
import com.javiblzq.assistant.data.SmartBulbDevice
import com.javiblzq.assistant.data.TodayTrainingItem
import com.javiblzq.assistant.power.DisplayMode
import com.javiblzq.assistant.providers.StravaAnalyzer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

class DashboardRenderer(
    private val context: Context,
    private val onRequestWake: () -> Unit,
    private val onRequestSync: () -> Unit,
    private val onToggleTask: (String) -> Unit,
    private val onToggleBulb: (String) -> Unit = {},
    private val onRequestCommuteSync: (CommuteTarget) -> Unit = {},
    private val onTapSatelliteRadar: () -> Unit = {}
) {
    enum class CommuteTarget { WORK, GYM }

    private var activeCommuteTarget: CommuteTarget = CommuteTarget.WORK
    private var qrModalVisible: Boolean = false
    private var lastQrUrl: String = ""
    private var lastRenderedBulbs: List<SmartBulbDevice>? = null

    val rootView: FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(BrutalistTheme.BG_BLACK)
        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }

    // Mode 1: Low-Power Always-On Display (09:00 - 18:00 idle)
    private val lowPowerRoot: FrameLayout
    private val aodShiftBox: LinearLayout
    private val aodClockText: TextView
    private val aodDateText: TextView
    private val aodWeatherGlyph: DotMatrixWeatherGlyphView
    private val aodWeatherText: TextView
    private val aodCommuteText: TextView
    private val aodSportText: TextView
    private val aodHintText: TextView

    // Mode 2: Night Blackout (18:00 - 09:00 idle)
    private val blackoutRoot: View

    // Mode 3: Full Dashboard
    private val fullRoot: LinearLayout
    private val topClockText: TextView
    private val topDateText: TextView
    private val topTelemetryText: TextView
    private val btnSync: TextView
    private val btnQr: TextView

    // Col 1 Top: Weather panel (01 // CLIMA)
    private val weatherGlyph: DotMatrixWeatherGlyphView
    private val weatherTempText: TextView
    private val weatherHiLoText: TextView
    private val weatherCondText: TextView
    private val weatherMetaText: TextView
    private val weatherEmptyText: TextView
    private val weatherCurveView: AppleWeatherCurveView

    // Col 1 Bottom: Commute panel (02 // RUTA COCHE)
    private val commuteFreshnessText: TextView
    private val commuteWorkCard: LinearLayout
    private val commuteWorkLabel: TextView
    private val commuteWorkValue: TextView
    private val commuteWorkMeta: TextView
    private val commuteGymCard: LinearLayout
    private val commuteGymLabel: TextView
    private val commuteGymValue: TextView
    private val commuteGymMeta: TextView
    private val routeMiniMapView: RouteMiniMapView

    // Col 2 Top: Unified Agenda & Today's Training (03 // AGENDA & ENTRENO DE HOY)
    private val todayTrainingList: LinearLayout
    private val agendaList: LinearLayout

    // Col 2 Mid: Satellite Radar Sky-Plot (04 // RADAR SATELITAL GNSS (EU / US))
    private var showGnss = true
    private var latestSatelliteSky: com.javiblzq.assistant.data.SatelliteSkySnapshot? = null
    private val satelliteSourceText: TextView
    private val satelliteRadarView: SatelliteRadarView
    private val satelliteCountsText: TextView
    private val satelliteFocusText: TextView

    // Col 2 Bottom: Weekly Training Progress (05 // ENTRENOS SEMANA (DESDE EL LUNES))
    private val weeklyCounterText: TextView
    private val weeklyCounterLabel: TextView
    private val weekStripView: WeekStripView
    private val weekSummaryText: TextView

    // Col 3 Top: Markets (06 // MERCADOS (MSCI WORLD & ACCIONES))
    private val marketSymbolText: TextView
    private val marketPriceText: TextView
    private val marketDeltaText: TextView
    private val marketSparklineView: MarketSparklineView
    private val watchlistRow: LinearLayout

    // Col 3 Mid: Smart Home & Semyra Status (06 // DOMÓTICA TAPO & ESTADO SEMYRA)
    private val bulbsContainer: LinearLayout
    private val serviceHealthText: TextView

    // Col 3 Bottom: Tasks & Notes via Telegram Bot (07 // TAREAS & NOTAS (TELEGRAM BOT))
    private val telegramStatusText: TextView
    private val pinnedNoteText: TextView
    private val tasksList: LinearLayout

    // QR Overlay
    private lateinit var qrModalOverlay: FrameLayout
    private val qrImageView: ImageView
    private val qrUrlText: TextView

    init {
        BrutalistTheme.ensureLoaded(context)

        // --- LOW POWER AOD ---
        lowPowerRoot = FrameLayout(context).apply {
            setBackgroundColor(BrutalistTheme.BG_BLACK)
            setOnClickListener { onRequestWake() }
        }
        aodShiftBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32f), dp(24f), dp(32f), dp(24f))
        }
        aodClockText = makeText(86f, BrutalistTheme.FG_WHITE, display = true).apply {
            gravity = Gravity.CENTER
        }
        aodDateText = makeText(18f, BrutalistTheme.FG_MUTED, mono = true).apply {
            gravity = Gravity.CENTER
        }
        val aodWeatherRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(22f), 0, dp(10f))
        }
        aodWeatherGlyph = DotMatrixWeatherGlyphView(context)
        aodWeatherRow.addView(aodWeatherGlyph, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
            rightMargin = dp(14f)
        })
        aodWeatherText = makeText(22f, BrutalistTheme.FG_WHITE, display = true)
        aodWeatherRow.addView(aodWeatherText)

        aodCommuteText = makeText(15f, BrutalistTheme.FG_MUTED, mono = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8f), 0, dp(6f))
        }
        aodSportText = makeText(15f, BrutalistTheme.FG_WHITE, compact = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            background = borderDrawable(BrutalistTheme.BORDER)
        }
        aodHintText = makeText(12f, BrutalistTheme.FG_DIM, mono = true).apply {
            gravity = Gravity.CENTER
            text = "[ TOCAR PANTALLA // MODO BAJO CONSUMO ]"
            setPadding(0, dp(28f), 0, 0)
        }
        aodShiftBox.addView(aodClockText)
        aodShiftBox.addView(aodDateText)
        aodShiftBox.addView(aodWeatherRow)
        aodShiftBox.addView(aodCommuteText)
        aodShiftBox.addView(aodSportText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(10f)
        })
        aodShiftBox.addView(aodHintText)
        lowPowerRoot.addView(aodShiftBox, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // --- NIGHT BLACKOUT ---
        blackoutRoot = View(context).apply {
            setBackgroundColor(BrutalistTheme.BG_BLACK)
            setOnClickListener { onRequestWake() }
        }

        // --- FULL DASHBOARD ---
        fullRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BrutalistTheme.BG_BLACK)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }

        // First Card (Top Bar — Hora y Fecha, layout intact with Doto clock digits)
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panelDrawable()
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        }
        topClockText = makeText(30f, BrutalistTheme.FG_WHITE, display = true)
        topDateText = makeText(14f, BrutalistTheme.FG_MUTED, mono = true).apply {
            setPadding(dp(16f), 0, 0, 0)
        }
        topTelemetryText = makeText(12f, BrutalistTheme.FG_MUTED, mono = true).apply {
            gravity = Gravity.END
        }
        btnSync = makeActionButton("[SYNC]") { onRequestSync() }
        btnQr = makeActionButton("[QR CONFIG]") {
            qrModalVisible = true
            qrModalOverlay.visibility = View.VISIBLE
            onRequestWake()
        }
        topBar.addView(topClockText)
        topBar.addView(topDateText)
        topBar.addView(topTelemetryText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            rightMargin = dp(12f)
        })
        topBar.addView(btnSync, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            rightMargin = dp(8f)
        })
        topBar.addView(btnQr)
        fullRoot.addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(8f)
        })

        // 3-Column Main Grid
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // COL 1: 01 // CLIMA + 02 // RUTA COCHE
        val col1 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val weatherBox = makePanel("01 // CLIMA")
        val wTop = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        weatherGlyph = DotMatrixWeatherGlyphView(context)
        wTop.addView(weatherGlyph, LinearLayout.LayoutParams(dp(42f), dp(42f)).apply {
            rightMargin = dp(10f)
        })
        weatherTempText = makeText(30f, BrutalistTheme.FG_WHITE, display = true)
        wTop.addView(weatherTempText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            rightMargin = dp(10f)
        })

        val wInfoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        weatherHiLoText = makeText(12f, BrutalistTheme.FG_WHITE, mono = true).apply {
            gravity = Gravity.END
            maxLines = 1
        }
        weatherCondText = makeText(12f, BrutalistTheme.FG_WHITE, compact = true).apply {
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        weatherMetaText = makeText(10f, BrutalistTheme.FG_MUTED, mono = true).apply {
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        wInfoCol.addView(weatherHiLoText)
        wInfoCol.addView(weatherCondText)
        wInfoCol.addView(weatherMetaText)
        wTop.addView(wInfoCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        weatherBox.addView(wTop)

        weatherBox.addView(
            View(context).apply { setBackgroundColor(0xFF1A1A1A.toInt()) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)).apply {
                topMargin = dp(6f)
                bottomMargin = dp(4f)
            }
        )

        weatherEmptyText = makeText(11f, BrutalistTheme.FG_MUTED, mono = true).apply {
            text = "ESPERANDO RED PARA CURVA 24H..."
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        weatherBox.addView(weatherEmptyText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(12f)
        })

        weatherCurveView = AppleWeatherCurveView(context)
        weatherBox.addView(weatherCurveView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        col1.addView(weatherBox, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.08f).apply {
            bottomMargin = dp(8f)
        })

        val commuteBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelDrawable()
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        val commuteTitleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4f))
        }
        commuteTitleRow.addView(makeText(13f, BrutalistTheme.FG_WHITE, dots = true).apply {
            text = "02 // RUTA COCHE"
            maxLines = 1
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        commuteFreshnessText = makeText(9.5f, BrutalistTheme.FG_MUTED, mono = true).apply {
            text = "PENDIENTE"
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        commuteTitleRow.addView(commuteFreshnessText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            leftMargin = dp(8f)
        })
        commuteBox.addView(commuteTitleRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        commuteBox.addView(
            View(context).apply { setBackgroundColor(BrutalistTheme.BORDER) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)).apply { bottomMargin = dp(6f) }
        )

        val commuteHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        commuteWorkCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = borderDrawable(BrutalistTheme.BORDER_BRIGHT, BrutalistTheme.BG_ELEVATED)
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            setOnClickListener {
                activeCommuteTarget = CommuteTarget.WORK
                commuteFreshnessText.text = "⟳ ACTUALIZANDO..."
                commuteFreshnessText.setTextColor(BrutalistTheme.FG_WHITE)
                onRequestWake()
                onRequestCommuteSync(CommuteTarget.WORK)
            }
        }
        commuteWorkLabel = makeText(10f, BrutalistTheme.FG_WHITE, mono = true).apply {
            text = "TRABAJO"
            maxLines = 1
        }
        commuteWorkValue = makeText(22f, BrutalistTheme.FG_WHITE, display = true).apply {
            maxLines = 1
        }
        commuteWorkMeta = makeText(10f, BrutalistTheme.FG_MUTED, mono = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        commuteWorkCard.addView(commuteWorkLabel)
        commuteWorkCard.addView(commuteWorkValue)
        commuteWorkCard.addView(commuteWorkMeta)

        commuteGymCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = borderDrawable(BrutalistTheme.BORDER, BrutalistTheme.BG_BLACK)
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            setOnClickListener {
                activeCommuteTarget = CommuteTarget.GYM
                commuteFreshnessText.text = "⟳ ACTUALIZANDO..."
                commuteFreshnessText.setTextColor(BrutalistTheme.FG_WHITE)
                onRequestWake()
                onRequestCommuteSync(CommuteTarget.GYM)
            }
        }
        commuteGymLabel = makeText(10f, BrutalistTheme.FG_MUTED, mono = true).apply {
            text = "GYM"
            maxLines = 1
        }
        commuteGymValue = makeText(22f, BrutalistTheme.FG_WHITE, display = true).apply {
            maxLines = 1
        }
        commuteGymMeta = makeText(10f, BrutalistTheme.FG_MUTED, mono = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        commuteGymCard.addView(commuteGymLabel)
        commuteGymCard.addView(commuteGymValue)
        commuteGymCard.addView(commuteGymMeta)

        commuteHeaderRow.addView(commuteWorkCard, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            rightMargin = dp(6f)
        })
        commuteHeaderRow.addView(commuteGymCard, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        commuteBox.addView(commuteHeaderRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(6f)
        })

        routeMiniMapView = RouteMiniMapView(context).apply {
            setOnClickListener {
                commuteFreshnessText.text = "⟳ ACTUALIZANDO..."
                commuteFreshnessText.setTextColor(BrutalistTheme.FG_WHITE)
                onRequestWake()
                onRequestCommuteSync(activeCommuteTarget)
            }
        }
        commuteBox.addView(routeMiniMapView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        col1.addView(commuteBox, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.0f))

        grid.addView(col1, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.12f).apply {
            rightMargin = dp(8f)
        })

        // COL 2: 03 // AGENDA & ENTRENO DE HOY + 04 // RADAR SATELITAL GNSS + 05 // ENTRENOS SEMANA
        val col2 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val agendaBox = makePanel("03 // AGENDA & ENTRENO DE HOY")
        todayTrainingList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        agendaBox.addView(todayTrainingList, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(6f)
        })
        agendaBox.addView(
            View(context).apply { setBackgroundColor(BrutalistTheme.BORDER) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)).apply {
                bottomMargin = dp(6f)
            }
        )
        val agendaScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        agendaList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        agendaScroll.addView(agendaList)
        agendaBox.addView(agendaScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        col2.addView(agendaBox, LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.85f).apply {
            bottomMargin = dp(8f)
        })

        // 04 // RADAR SATELITAL GNSS (EU / US)
        val radarBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelDrawable()
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        val radarTitleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4f))
        }
        radarTitleRow.addView(makeText(13f, BrutalistTheme.FG_WHITE, dots = true).apply {
            text = "04 // RADAR SATELITAL GNSS"
            maxLines = 1
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        satelliteSourceText = makeText(9.5f, BrutalistTheme.FG_MUTED, mono = true).apply {
            text = "ALMANAQUE"
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        radarTitleRow.addView(satelliteSourceText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            leftMargin = dp(8f)
        })
        radarTitleRow.addView(makeText(10f, BrutalistTheme.FG_WHITE, mono = true).apply {
            text = "[X] GNSS"
            contentDescription = "Mostrar satélites GNSS"
            setPadding(dp(8f), dp(6f), dp(4f), dp(6f))
            minHeight = dp(36f)
            background = panelDrawable()
            setOnClickListener {
                showGnss = !showGnss
                text = if (showGnss) "[X] GNSS" else "[ ] GNSS"
                contentDescription = if (showGnss) "Ocultar satélites GNSS" else "Mostrar satélites GNSS"
                onRequestWake()
                renderSatelliteSky()
            }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        radarBox.addView(radarTitleRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        radarBox.addView(
            View(context).apply { setBackgroundColor(BrutalistTheme.BORDER) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)).apply { bottomMargin = dp(4f) }
        )

        satelliteRadarView = SatelliteRadarView(context).apply {
            onSatelliteSelected = {
                renderSatelliteDetails()
                onRequestWake()
                onTapSatelliteRadar()
            }
        }
        radarBox.addView(satelliteRadarView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        radarBox.addView(
            View(context).apply { setBackgroundColor(0xFF1A1A1A.toInt()) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)).apply {
                topMargin = dp(4f)
                bottomMargin = dp(4f)
            }
        )
        satelliteCountsText = makeText(10f, BrutalistTheme.FG_WHITE, mono = true).apply {
            text = "◆ EU GAL/ESA: --   ● US GPS/NASA: --"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        satelliteFocusText = makeText(9.5f, BrutalistTheme.FG_MUTED, mono = true).apply {
            text = "TOCA UN SATÉLITE PARA VER SU FICHA"
            maxLines = 5
            setPadding(dp(5f), dp(3f), dp(5f), dp(3f))
            background = panelDrawable()
        }
        radarBox.addView(satelliteCountsText)
        radarBox.addView(satelliteFocusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(2f)
        })

        col2.addView(radarBox, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.15f).apply {
            bottomMargin = dp(8f)
        })

        val weekBox = makePanel("05 // ENTRENOS SEMANA (DESDE EL LUNES)")
        val weekTopRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val weekHeroCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            minimumWidth = dp(112f)
        }
        weeklyCounterText = makeText(36f, BrutalistTheme.FG_WHITE, display = true).apply {
            text = "0/6"
            maxLines = 1
        }
        weeklyCounterLabel = makeText(10f, BrutalistTheme.FG_MUTED, mono = true).apply {
            text = "COMPLETADOS ESTA SEMANA"
            maxLines = 2
        }
        weekHeroCol.addView(weeklyCounterText)
        weekHeroCol.addView(weeklyCounterLabel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(2f)
        })

        weekStripView = WeekStripView(context)
        weekTopRow.addView(weekHeroCol, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            rightMargin = dp(10f)
        })
        weekTopRow.addView(weekStripView, LinearLayout.LayoutParams(0, dp(64f), 1f))
        weekBox.addView(weekTopRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(8f)
        })
        weekBox.addView(
            View(context).apply { setBackgroundColor(0xFF1A1A1A.toInt()) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)).apply {
                bottomMargin = dp(6f)
            }
        )
        weekSummaryText = makeText(11f, BrutalistTheme.FG_MUTED, mono = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        weekBox.addView(weekSummaryText)
        col2.addView(weekBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        grid.addView(col2, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.15f).apply {
            rightMargin = dp(8f)
        })

        // COL 3: 06 // MERCADOS + 07 // DOMÓTICA TAPO & ESTADO SEMYRA + 08 // TAREAS & NOTAS
        val col3 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Panel 1: 06 // MERCADOS (MSCI WORLD & ACCIONES)
        val marketBox = makePanel("06 // MERCADOS (MSCI WORLD & ACCIONES)")
        val marketHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val marketTitleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        marketSymbolText = makeText(10.5f, BrutalistTheme.FG_WHITE, mono = true).apply {
            text = "IWDA.AS // MSCI WORLD"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        marketDeltaText = makeText(10f, BrutalistTheme.FG_MUTED, mono = true).apply {
            text = "CARGANDO COTIZACIÓN..."
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        marketTitleCol.addView(marketSymbolText)
        marketTitleCol.addView(marketDeltaText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(1f)
        })
        marketPriceText = makeText(18f, BrutalistTheme.FG_WHITE, display = true).apply {
            text = "--"
            maxLines = 1
            gravity = Gravity.END
        }
        marketHeaderRow.addView(marketTitleCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            rightMargin = dp(8f)
        })
        marketHeaderRow.addView(marketPriceText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        marketBox.addView(marketHeaderRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(4f)
        })

        marketSparklineView = MarketSparklineView(context)
        marketBox.addView(marketSparklineView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
            bottomMargin = dp(5f)
        })

        marketBox.addView(
            View(context).apply { setBackgroundColor(0xFF1A1A1A.toInt()) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)).apply {
                bottomMargin = dp(5f)
            }
        )

        watchlistRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        marketBox.addView(watchlistRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col3.addView(marketBox, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.05f).apply {
            bottomMargin = dp(8f)
        })

        // Panel 2: 07 // DOMÓTICA TAPO & ESTADO SEMYRA
        val smartHomeBox = makePanel("07 // DOMÓTICA TAPO & ESTADO SEMYRA")
        bulbsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        smartHomeBox.addView(bulbsContainer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(5f)
        })
        smartHomeBox.addView(
            View(context).apply { setBackgroundColor(0xFF1A1A1A.toInt()) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)).apply {
                bottomMargin = dp(5f)
            }
        )
        serviceHealthText = makeText(9.5f, BrutalistTheme.FG_MUTED, mono = true).apply {
            text = "ECOSISTEMA SEMYRA: COMPROBANDO..."
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        smartHomeBox.addView(serviceHealthText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col3.addView(smartHomeBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(8f)
        })

        // Panel 3: 08 // TAREAS & NOTAS (TELEGRAM BOT)
        val tasksBox = makePanel("08 // TAREAS & NOTAS (TELEGRAM BOT)")
        telegramStatusText = makeText(10.5f, BrutalistTheme.FG_MUTED, mono = true).apply {
            text = "TELEGRAM BOT: INICIANDO"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        tasksBox.addView(telegramStatusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(5f)
        })
        pinnedNoteText = makeText(11.5f, BrutalistTheme.FG_WHITE, compact = true).apply {
            background = borderDrawable(BrutalistTheme.BORDER_BRIGHT, BrutalistTheme.BG_ELEVATED)
            setPadding(dp(7f), dp(5f), dp(7f), dp(5f))
            visibility = View.GONE
        }
        tasksBox.addView(pinnedNoteText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(5f)
        })
        val taskScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        tasksList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        taskScroll.addView(tasksList)
        tasksBox.addView(taskScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        col3.addView(tasksBox, LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.95f))

        grid.addView(col3, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.02f))
        fullRoot.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // --- QR MODAL OVERLAY ---
        qrModalOverlay = FrameLayout(context).apply {
            setBackgroundColor(0xEA000000.toInt())
            visibility = View.GONE
            setOnClickListener {
                qrModalVisible = false
                visibility = View.GONE
            }
        }
        val qrCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = panelDrawable(BrutalistTheme.BORDER_BRIGHT)
            setPadding(dp(24f), dp(20f), dp(24f), dp(20f))
        }
        val qrTitle = makeText(18f, BrutalistTheme.FG_WHITE, dots = true).apply {
            text = "CONFIGURAR DESDE EL MOVIL (MISMA WIFI)"
            gravity = Gravity.CENTER
        }
        qrImageView = ImageView(context).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        }
        qrUrlText = makeText(18f, BrutalistTheme.FG_WHITE, mono = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12f), 0, dp(8f))
        }
        val qrClose = makeActionButton("[ CERRAR ]") {
            qrModalVisible = false
            qrModalOverlay.visibility = View.GONE
        }
        qrCard.addView(qrTitle)
        qrCard.addView(qrImageView, LinearLayout.LayoutParams(dp(220f), dp(220f)).apply {
            topMargin = dp(14f)
        })
        qrCard.addView(qrUrlText)
        qrCard.addView(qrClose)
        qrModalOverlay.addView(
            qrCard,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
        )

        rootView.addView(fullRoot, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        rootView.addView(lowPowerRoot, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        rootView.addView(blackoutRoot, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        rootView.addView(qrModalOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun render(
        mode: DisplayMode,
        config: AssistantConfig,
        snapshot: DashboardSnapshot,
        batteryPct: Int,
        isCharging: Boolean,
        lanIp: String,
        serverPort: Int
    ) {
        val tz = TimeZone.getTimeZone(config.timezone)
        val now = Date()
        val hhmm = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }.format(now)
        val hhmmss = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = tz }.format(now)
        val dateStr = SimpleDateFormat("EEE dd MMM yyyy", Locale("es", "ES")).apply { timeZone = tz }
            .format(now).uppercase(Locale.US)
        val cal = Calendar.getInstance(tz).apply { time = now }
        val weekNum = cal.get(Calendar.WEEK_OF_YEAR)

        lowPowerRoot.visibility = if (mode == DisplayMode.LOW_POWER_AOD) View.VISIBLE else View.GONE
        blackoutRoot.visibility = if (mode == DisplayMode.NIGHT_BLACKOUT) View.VISIBLE else View.GONE
        fullRoot.visibility = if (mode == DisplayMode.FULL_DASHBOARD) View.VISIBLE else View.GONE

        if (mode != DisplayMode.FULL_DASHBOARD && qrModalVisible) {
            qrModalVisible = false
            qrModalOverlay.visibility = View.GONE
        }

        when (mode) {
            DisplayMode.NIGHT_BLACKOUT -> return
            DisplayMode.LOW_POWER_AOD -> {
                // Subtle anti-burn-in shift every minute
                val minute = cal.get(Calendar.MINUTE)
                val shiftX = ((minute % 7) - 3) * dp(4f)
                val shiftY = (((minute / 7) % 5) - 2) * dp(4f)
                aodShiftBox.translationX = shiftX.toFloat()
                aodShiftBox.translationY = shiftY.toFloat()

                aodClockText.text = hhmm
                aodDateText.text = "$dateStr  //  SEM $weekNum  //  BAT $batteryPct%"
                val w = snapshot.weather
                if (w != null) {
                    aodWeatherGlyph.setWeatherCode(w.weatherCode)
                    aodWeatherText.text = "${w.currentTempC.roundToInt()}°C // ${w.conditionText}"
                } else {
                    aodWeatherGlyph.setWeatherCode(0)
                    aodWeatherText.text = "--°C // SIN DATOS CLIMA"
                }

                val workMin = snapshot.workCommute?.let { "${it.durationMin} MIN" } ?: "--"
                val gymMin = snapshot.gymCommute?.let { "${it.durationMin} MIN" } ?: "--"
                aodCommuteText.text = "COCHE  ${config.workLabel}: $workMin   |   ${config.gymLabel}: $gymMin"

                val todayTrainings = resolveTodayTrainings(snapshot, tz)
                val firstTraining = todayTrainings.firstOrNull()
                val lastRun = snapshot.latestRunAnalysis
                aodSportText.text = when {
                    firstTraining != null -> formatTodayTrainingLine(firstTraining)
                    lastRun != null -> "ULT RUN: ${String.format(Locale.US, "%.1f", lastRun.activity.distanceM / 1000.0)} KM @ ${lastRun.averagePaceFormatted}"
                    else -> "SEMYRA SPORT // CONECTAR EN HTTP://$lanIp:$serverPort"
                }
                return
            }
            DisplayMode.FULL_DASHBOARD -> Unit
        }

        // First Card: Top Bar (Hora y Fecha)
        topClockText.text = hhmmss
        topDateText.text = "$dateStr  //  SEM $weekNum"
        val chg = if (isCharging) "+" else ""
        val syncText = if (snapshot.lastSyncMs > 0L) {
            SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }.format(Date(snapshot.lastSyncMs))
        } else "--:--"
        topTelemetryText.text = "BAT $chg$batteryPct%  |  http://$lanIp:$serverPort  |  SYNC $syncText"

        // QR URL
        val portalUrl = "http://$lanIp:$serverPort"
        if (portalUrl != lastQrUrl) {
            lastQrUrl = portalUrl
            qrUrlText.text = portalUrl
            BrutalistTheme.generateQrBitmap(portalUrl, 320)?.let {
                qrImageView.setImageBitmap(it)
            }
        }

        // Col 1 Top: Weather (01 // CLIMA)
        renderWeather(snapshot)

        // Col 1 Bottom: Commute (02 // RUTA COCHE)
        renderCommute(config, snapshot)

        // Col 2 Top: Agenda & Today's Training (03 // AGENDA & ENTRENO DE HOY)
        renderAgendaAndToday(snapshot, tz)

        // Col 2 Mid: Satellite Radar (04 // RADAR SATELITAL GNSS)
        renderSatellites(snapshot)

        // Col 2 Bottom: Weekly Training Progress (05 // ENTRENOS SEMANA (DESDE EL LUNES))
        renderWeeklyProgress(config, snapshot)

        // Col 3: Markets (05), Smart Home & Semyra Health (06), Tasks & Notes (07)
        renderMarkets(config, snapshot)
        renderSmartHomeAndServices(config, snapshot)
        renderTasksAndTelegram(config, snapshot)
    }

    private fun renderWeather(snapshot: DashboardSnapshot) {
        val w = snapshot.weather
        if (w == null) {
            weatherGlyph.setWeatherCode(0)
            weatherTempText.text = "--°C"
            weatherHiLoText.text = "H --° · L --°"
            weatherCondText.text = snapshot.weatherStatus
            weatherMetaText.text = "ESPERANDO RED"
            weatherEmptyText.visibility = View.VISIBLE
            weatherCurveView.visibility = View.GONE
            return
        }

        weatherGlyph.setWeatherCode(w.weatherCode)
        weatherTempText.text = "${w.currentTempC.roundToInt()}°C"

        val curveItems = w.today24h.ifEmpty { w.hourly }
        val hiC = if (w.today24h.isNotEmpty() || w.todayMaxC != 0.0 || w.todayMinC != 0.0) {
            w.todayMaxC
        } else {
            w.daily.firstOrNull()?.maxC ?: curveItems.maxOfOrNull { it.tempC } ?: w.currentTempC
        }
        val loC = if (w.today24h.isNotEmpty() || w.todayMaxC != 0.0 || w.todayMinC != 0.0) {
            w.todayMinC
        } else {
            w.daily.firstOrNull()?.minC ?: curveItems.minOfOrNull { it.tempC } ?: w.currentTempC
        }

        weatherHiLoText.text = "H ${hiC.roundToInt()}° · L ${loC.roundToInt()}°"
        weatherCondText.text = w.conditionText
        weatherMetaText.text = "ST ${w.apparentTempC.roundToInt()}°C · VIENTO ${w.windKmh.roundToInt()} KM/H"

        if (curveItems.isEmpty()) {
            weatherEmptyText.visibility = View.VISIBLE
            weatherCurveView.visibility = View.GONE
        } else {
            weatherEmptyText.visibility = View.GONE
            weatherCurveView.visibility = View.VISIBLE
            weatherCurveView.setHourlyData(
                tempsC = curveItems.map { it.tempC },
                weatherCodes = curveItems.map { it.weatherCode },
                hourLabels = curveItems.map { it.hourLabel }
            )
        }
    }

    private fun renderCommute(config: AssistantConfig, snapshot: DashboardSnapshot) {
        val work = snapshot.workCommute
        val gym = snapshot.gymCommute

        val workLabel = config.workLabel.ifBlank { "TRABAJO" }
        val gymLabel = config.gymLabel.ifBlank { "GYM" }

        val workSelected = activeCommuteTarget == CommuteTarget.WORK
        commuteWorkCard.background = borderDrawable(
            if (workSelected) BrutalistTheme.BORDER_BRIGHT else BrutalistTheme.BORDER,
            if (workSelected) BrutalistTheme.BG_ELEVATED else BrutalistTheme.BG_BLACK
        )
        commuteWorkLabel.setTextColor(if (workSelected) BrutalistTheme.FG_WHITE else BrutalistTheme.FG_MUTED)
        commuteWorkLabel.text = if (workSelected) "> $workLabel" else workLabel
        commuteWorkValue.text = if (work != null && work.durationMin > 0) "${work.durationMin} MIN" else "-- MIN"
        commuteWorkMeta.text = formatCommuteMeta(work, config.homeLabel, workLabel)
        commuteWorkMeta.setTextColor(commuteMetaColor(work))

        val gymSelected = activeCommuteTarget == CommuteTarget.GYM
        commuteGymCard.background = borderDrawable(
            if (gymSelected) BrutalistTheme.BORDER_BRIGHT else BrutalistTheme.BORDER,
            if (gymSelected) BrutalistTheme.BG_ELEVATED else BrutalistTheme.BG_BLACK
        )
        commuteGymLabel.setTextColor(if (gymSelected) BrutalistTheme.FG_WHITE else BrutalistTheme.FG_MUTED)
        commuteGymLabel.text = if (gymSelected) "> $gymLabel" else gymLabel
        commuteGymValue.text = if (gym != null && gym.durationMin > 0) "${gym.durationMin} MIN" else "-- MIN"
        commuteGymMeta.text = formatCommuteMeta(gym, config.homeLabel, gymLabel)
        commuteGymMeta.setTextColor(commuteMetaColor(gym))

        val activeRoute = if (workSelected) work else gym
        val activeDestLabel = if (workSelected) workLabel else gymLabel

        if (snapshot.commuteSyncing) {
            commuteFreshnessText.text = "⟳ ACTUALIZANDO..."
            commuteFreshnessText.setTextColor(BrutalistTheme.FG_WHITE)
        } else if (activeRoute != null && activeRoute.fetchedAtMs > 0L) {
            val freshness = formatFreshness(activeRoute.fetchedAtMs)
            val providerTag = activeRoute.providerLabel.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
            commuteFreshnessText.text = "$freshness$providerTag"
            commuteFreshnessText.setTextColor(BrutalistTheme.FG_MUTED)
        } else {
            commuteFreshnessText.text = "SIN ACTUALIZAR"
            commuteFreshnessText.setTextColor(BrutalistTheme.FG_DIM)
        }

        if (activeRoute != null) {
            val pts = activeRoute.polylinePoints
            routeMiniMapView.setRouteGeometry(
                lats = pts.map { it.lat }.toDoubleArray(),
                lons = pts.map { it.lon }.toDoubleArray(),
                congestionLevels = pts.map { it.congestionLevel }.toIntArray(),
                originLabel = activeRoute.originLabel.ifBlank { config.homeLabel },
                destLabel = activeRoute.targetName.ifBlank { activeDestLabel },
                delayMin = activeRoute.trafficDelayMin
            )
        } else {
            routeMiniMapView.setRouteGeometry(
                lats = DoubleArray(0),
                lons = DoubleArray(0),
                congestionLevels = IntArray(0),
                originLabel = config.homeLabel,
                destLabel = activeDestLabel,
                delayMin = 0
            )
        }
    }

    private fun formatCommuteMeta(route: CommuteRoute?, originLabel: String, destLabel: String): String {
        return if (route != null && route.distanceKm > 0.0) {
            "${route.distanceKm} KM · ${route.statusLabel}"
        } else {
            "$originLabel -> $destLabel · SIN RUTA"
        }
    }

    private fun commuteMetaColor(route: CommuteRoute?): Int {
        if (route == null) return BrutalistTheme.FG_MUTED
        val upperStatus = route.statusLabel.uppercase(Locale.US)
        return when {
            upperStatus.startsWith("TOMTOM ERR") || upperStatus.startsWith("TOMTOM SIN") || upperStatus.startsWith("TOMTOM PARSE") ->
                BrutalistTheme.ACCENT_AMBER
            route.trafficDelayMin >= 5 -> BrutalistTheme.TRAFFIC_RED_HIGH
            route.trafficDelayMin >= 1 -> BrutalistTheme.TRAFFIC_RED_MED
            else -> BrutalistTheme.FG_MUTED
        }
    }

    private fun renderAgendaAndToday(snapshot: DashboardSnapshot, tz: TimeZone) {
        todayTrainingList.removeAllViews()
        val trainings = resolveTodayTrainings(snapshot, tz)
        if (trainings.isEmpty()) {
            todayTrainingList.addView(makeText(11f, BrutalistTheme.FG_DIM, mono = true).apply {
                text = "HOY // SIN ENTRENO PROGRAMADO"
                background = borderDrawable(0xFF1A1A1A.toInt(), BrutalistTheme.BG_BLACK)
                setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        } else {
            trainings.forEachIndexed { idx, item ->
                val pill = makeText(13f, BrutalistTheme.FG_WHITE, display = true).apply {
                    text = formatTodayTrainingLine(item)
                    background = borderDrawable(BrutalistTheme.BORDER_BRIGHT, BrutalistTheme.BG_ELEVATED)
                    setPadding(dp(9f), dp(7f), dp(9f), dp(7f))
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                todayTrainingList.addView(pill, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    if (idx < trainings.lastIndex) bottomMargin = dp(4f)
                })
            }
        }

        agendaList.removeAllViews()
        val events = snapshot.calendarEvents
        if (events.isEmpty()) {
            agendaList.addView(makeText(11f, BrutalistTheme.FG_MUTED, mono = true).apply {
                text = "SIN EVENTOS PROXIMOS.\nAnade un feed .ics o evento desde la web del movil."
            })
            return
        }
        for (ev in events.take(10)) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = borderDrawable(
                    if (ev.isToday) 0xFF454545.toInt() else 0xFF1A1A1A.toInt(),
                    if (ev.isToday) BrutalistTheme.BG_ELEVATED else BrutalistTheme.BG_BLACK
                )
                setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            }
            val dayTag = if (ev.isToday) "HOY" else ev.dateIso.takeLast(5)
            card.addView(makeText(11f, if (ev.isToday) BrutalistTheme.FG_WHITE else BrutalistTheme.FG_MUTED, mono = true).apply {
                text = "$dayTag // ${ev.startTime}" + (if (ev.endTime.isNotBlank()) "-${ev.endTime}" else "")
            })
            card.addView(makeText(12f, BrutalistTheme.FG_WHITE, compact = true).apply {
                text = ev.title
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (ev.location.isNotBlank()) {
                card.addView(makeText(10f, BrutalistTheme.FG_DIM, mono = true).apply {
                    text = "@ ${ev.location}"
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            agendaList.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(4f)
            })
        }
    }

    private fun resolveTodayTrainings(snapshot: DashboardSnapshot, tz: TimeZone): List<TodayTrainingItem> {
        if (snapshot.todayTrainings.isNotEmpty()) return snapshot.todayTrainings
        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.format(Date())
        val fallback = mutableListOf<TodayTrainingItem>()
        val todayW = snapshot.todayWorkout
        if (todayW != null && todayW.scheduledDateIso == todayIso) {
            // Never expose exercise descriptions or set lists for strength sessions
            fallback.add(TodayTrainingItem(type = "STRENGTH", title = todayW.title, subtitle = ""))
        }
        for (act in snapshot.stravaActivities) {
            if (act.dateIso != todayIso) continue
            if (act.isRun) {
                val distKm = String.format(Locale.US, "%.1f KM", act.distanceM / 1000.0)
                val durMin = act.movingTimeS / 60
                val pace = StravaAnalyzer.formatPace(act.paceSecondsPerKm)
                val desc = if (pace != "—") "$distKm · ${durMin}' · $pace" else "$distKm · ${durMin}'"
                fallback.add(TodayTrainingItem(type = "RUN", title = act.name, subtitle = desc))
            } else if (act.isStrength && fallback.none { it.type.equals("STRENGTH", ignoreCase = true) }) {
                fallback.add(TodayTrainingItem(type = "STRENGTH", title = act.name, subtitle = ""))
            }
        }
        return fallback
    }

    private fun formatTodayTrainingLine(item: TodayTrainingItem): String {
        val cleanTitle = item.title.trim().ifBlank { "SESION" }.uppercase(Locale.US)
        val cleanSub = item.subtitle.trim()
        return when {
            item.type.equals("STRENGTH", ignoreCase = true) ->
                "[ENTRENO FUERZA] // $cleanTitle"
            item.type.equals("RUN", ignoreCase = true) || item.type.equals("RUNNING", ignoreCase = true) ->
                if (cleanSub.isNotBlank()) "[CARRERA] // $cleanTitle — $cleanSub"
                else "[CARRERA] // $cleanTitle"
            else ->
                "[${item.type.uppercase(Locale.US)}] // $cleanTitle"
        }
    }

    private fun renderSatellites(snapshot: DashboardSnapshot) {
        latestSatelliteSky = snapshot.satellites
        renderSatelliteSky()
    }

    private fun renderSatelliteSky() {
        val sky = latestSatelliteSky?.withGnssVisibility(showGnss)
        satelliteRadarView.setSkySnapshot(sky)
        if (sky == null) {
            satelliteSourceText.text = "CALCULANDO..."
            satelliteCountsText.text = "◆ EU GAL/ESA: --   ● US GPS/NASA: --"
            satelliteFocusText.text = "CENIT MAX: ESPERANDO EFEMÉRIDES"
            return
        }

        val gpsBadge = if (sky.hardwareFixActive) "GPS HW" else "CASA"
        satelliteSourceText.text = "${sky.almanacSource} · $gpsBadge"
        satelliteSourceText.setTextColor(
            if (sky.hardwareFixActive) 0xFF22C55E.toInt() else BrutalistTheme.FG_MUTED
        )

        val sb = SpannableStringBuilder()
        val euStart = sb.length
        sb.append("◆ EU: ${sky.euCount}")
        sb.setSpan(ForegroundColorSpan(0xFF38BDF8.toInt()), euStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("   ")
        val usStart = sb.length
        sb.append("● US: ${sky.usCount}")
        sb.setSpan(ForegroundColorSpan(0xFFF59E0B.toInt()), usStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (sky.hardwareSatCount > 0) {
            sb.append("   ")
            val hwStart = sb.length
            sb.append("ANTENA: ${sky.hardwareSatCount}")
            sb.setSpan(ForegroundColorSpan(0xFF22C55E.toInt()), hwStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        satelliteCountsText.text = sb

        renderSatelliteDetails()
    }

    private fun renderSatelliteDetails() {
        val focus = satelliteRadarView.getFocusedSatellite()
        if (focus == null) {
            satelliteFocusText.text = "SIN SATÉLITES >5° SOBRE EL HORIZONTE"
            satelliteFocusText.setTextColor(BrutalistTheme.FG_MUTED)
            return
        }
        val inclination = if (focus.inclinationDeg.isFinite())
            String.format(Locale.US, "%.1f°", focus.inclinationDeg) else "DESCONOCIDA"
        satelliteFocusText.text = String.format(Locale.US,
            "SATÉLITE: %s / %s · AZ %03d° EL %d°\nLANZAMIENTO: %s\nALTURA: %d KM · INCLINACIÓN: %s\nOPERADOR: %s",
            focus.name, focus.constellation, focus.azimuthDeg.roundToInt(), focus.elevationDeg.roundToInt(),
            focus.launchDate, focus.altitudeKm, inclination, focus.operator)
        satelliteFocusText.setTextColor(BrutalistTheme.FG_WHITE)
    }

    private fun renderWeeklyProgress(config: AssistantConfig, snapshot: DashboardSnapshot) {
        val ws = snapshot.weekSummary
        weekStripView.setSummary(ws)
        val completed = ws?.completedSessionsCount ?: 0
        val goal = config.weeklyTrainingGoal.coerceAtLeast(1)
        weeklyCounterText.text = "$completed/$goal"

        weekSummaryText.text = if (ws != null) {
            "${ws.runningCount} CARRERAS (${ws.runningDistanceKm} KM)  //  ${ws.strengthCount} FUERZA (${ws.completedSets} SETS)"
        } else {
            "0 CARRERAS (0.0 KM)  //  0 FUERZA (0 SETS)"
        }
    }

    private fun renderMarkets(config: AssistantConfig, snapshot: DashboardSnapshot) {
        val market = snapshot.market
        val primary = market?.primaryQuote
        if (primary != null) {
            val friendlyName = when (primary.symbol.uppercase(Locale.US)) {
                "IWDA.AS", "URTH", "^990100-USD-STRD" -> "MSCI WORLD"
                "SPY" -> "S&P 500"
                "QQQ" -> "NASDAQ 100"
                else -> primary.displayName.uppercase(Locale.US)
            }
            marketSymbolText.text = "${primary.symbol} // $friendlyName"
            val currSymbol = when (primary.currency.uppercase(Locale.US)) {
                "EUR" -> "€"
                "USD" -> "$"
                "GBP" -> "£"
                else -> primary.currency
            }
            marketPriceText.text = "${formatMarketPrice(primary.price)} $currSymbol".trim()
            marketDeltaText.text = "${formatSignedPct(primary.changePercent1d)} 1D  ·  ${formatSignedPct(primary.changePercent1m)} 1M"
            marketDeltaText.setTextColor(
                if (primary.changePercent1d >= 0.0) BrutalistTheme.FG_WHITE else BrutalistTheme.FG_MUTED
            )
            marketSparklineView.setSparklineData(primary.symbol, primary.sparklineCloses, primary.changePercent1m)
        } else {
            marketSymbolText.text = "${config.marketPrimarySymbol} // MSCI WORLD"
            marketPriceText.text = "--"
            marketDeltaText.text = market?.statusText ?: "SIN DATOS"
            marketDeltaText.setTextColor(BrutalistTheme.FG_MUTED)
            marketSparklineView.setSparklineData(config.marketPrimarySymbol, emptyList(), 0.0)
        }

        watchlistRow.removeAllViews()
        val watchQuotes = market?.watchlist.orEmpty().take(4)
        if (watchQuotes.isNotEmpty()) {
            watchQuotes.chunked(2).forEachIndexed { rowIdx, pair ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEachIndexed { colIdx, q ->
                    val badge = makeText(10f, BrutalistTheme.FG_WHITE, mono = true).apply {
                        text = "${q.symbol} ${formatMarketPrice(q.price)} (${formatSignedPct(q.changePercent1d)})"
                        background = borderDrawable(0xFF1E1E1E.toInt(), BrutalistTheme.BG_BLACK)
                        setPadding(dp(5f), dp(3f), dp(5f), dp(3f))
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    row.addView(badge, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                        if (colIdx == 0 && pair.size > 1) rightMargin = dp(4f)
                    })
                }
                watchlistRow.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    if (rowIdx > 0) topMargin = dp(3f)
                })
            }
        } else if (config.marketWatchlistSymbols.isNotEmpty()) {
            watchlistRow.addView(makeText(10f, BrutalistTheme.FG_DIM, mono = true).apply {
                text = config.marketWatchlistSymbols.take(4).joinToString("  |  ") { "$it --" }
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        } else {
            watchlistRow.addView(makeText(10f, BrutalistTheme.FG_DIM, mono = true).apply {
                text = "AÑADE ACCIONES EN WEB (:8080)"
                maxLines = 1
            })
        }
    }

    private fun renderSmartHomeAndServices(config: AssistantConfig, snapshot: DashboardSnapshot) {
        val bulbs = config.smartBulbs
        if (lastRenderedBulbs != bulbs) {
            lastRenderedBulbs = bulbs
            bulbsContainer.removeAllViews()
            if (bulbs.isEmpty()) {
                bulbsContainer.addView(makeText(10f, BrutalistTheme.FG_MUTED, mono = true).apply {
                    text = "BOMBILLAS TAPO/ALEXA: CONFIGURAR EN WEB (:8080)"
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
            } else {
                bulbs.chunked(2).forEachIndexed { rowIdx, pair ->
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    pair.forEachIndexed { colIdx, bulb ->
                        val labelName = bulb.name.trim().ifBlank { "LUZ" }.uppercase(Locale.US)
                        val btn = makeText(
                            10.5f,
                            if (bulb.isOn) BrutalistTheme.FG_WHITE else BrutalistTheme.FG_MUTED,
                            mono = true
                        ).apply {
                            text = if (bulb.isOn) "[ ☀ $labelName: ON ]" else "[ ○ $labelName: OFF ]"
                            gravity = Gravity.CENTER
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            background = borderDrawable(
                                if (bulb.isOn) BrutalistTheme.BORDER_BRIGHT else BrutalistTheme.BORDER,
                                if (bulb.isOn) BrutalistTheme.BG_ELEVATED else BrutalistTheme.BG_BLACK
                            )
                            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
                            setOnClickListener { onToggleBulb(bulb.id) }
                        }
                        row.addView(btn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                            if (colIdx == 0 && pair.size > 1) rightMargin = dp(5f)
                        })
                    }
                    bulbsContainer.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        if (rowIdx > 0) topMargin = dp(4f)
                    })
                }
            }
        }

        val health = snapshot.serviceHealth
        if (health.isEmpty()) {
            serviceHealthText.text = "SEMYRA ECOSYSTEM: COMPROBANDO..."
            serviceHealthText.setTextColor(BrutalistTheme.FG_MUTED)
        } else {
            val sb = SpannableStringBuilder()
            health.forEachIndexed { idx, item ->
                if (idx > 0) sb.append("  ")
                val start = sb.length
                val dot = if (item.online) "●" else "○"
                val shortName = shortServiceName(item)
                val lat = if (item.online && item.latencyMs >= 0) " ${item.latencyMs}ms" else ""
                sb.append("$dot $shortName$lat")
                val color = if (item.online) BrutalistTheme.FG_WHITE else BrutalistTheme.FG_DIM
                sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            serviceHealthText.text = sb
        }
    }

    private fun shortServiceName(item: ServiceHealthItem): String = when (item.id) {
        "harbiz" -> "HARBIZ"
        "open-meteo" -> "CLIMA"
        "osrm" -> "OSRM"
        "strava" -> "STRAVA"
        "semyra-sport" -> "SEMYRA"
        "telegram" -> "TG"
        else -> item.name.uppercase(Locale.US).take(12)
    }

    private fun formatMarketPrice(v: Double): String = when {
        v >= 1000.0 -> String.format(Locale.US, "%.0f", v)
        v >= 100.0 -> String.format(Locale.US, "%.1f", v)
        else -> String.format(Locale.US, "%.2f", v)
    }

    private fun formatSignedPct(v: Double): String {
        val sign = if (v >= 0.0) "+" else ""
        return String.format(Locale.US, "%s%.1f%%", sign, v)
    }

    private fun renderTasksAndTelegram(config: AssistantConfig, snapshot: DashboardSnapshot) {
        telegramStatusText.text = "TELEGRAM BOT: ${snapshot.telegramStatus}"
        val statusUpper = snapshot.telegramStatus.uppercase(Locale.US)
        telegramStatusText.setTextColor(
            if (statusUpper.contains("ACTIVO") || statusUpper.contains("CONECTADO") || statusUpper == "OK") {
                BrutalistTheme.FG_WHITE
            } else {
                BrutalistTheme.FG_MUTED
            }
        )

        if (config.pinnedNote.isNotBlank()) {
            pinnedNoteText.visibility = View.VISIBLE
            pinnedNoteText.text = "NOTA: ${config.pinnedNote}"
        } else {
            pinnedNoteText.visibility = View.GONE
        }

        tasksList.removeAllViews()
        val tasks = visibleTasks(config.tasks)
        if (tasks.isEmpty()) {
            tasksList.addView(makeText(11f, BrutalistTheme.FG_MUTED, mono = true).apply {
                text = "SIN TAREAS ACTIVAS.\nEnvia tareas desde Telegram Bot o la web local."
            })
            return
        }
        for (t in tasks) {
            val urgency = t.normalizedUrgency
            val accentColor = when (urgency) {
                com.javiblzq.assistant.data.QuickTask.URGENCY_HIGH -> BrutalistTheme.ACCENT_RED
                com.javiblzq.assistant.data.QuickTask.URGENCY_MED -> BrutalistTheme.ACCENT_AMBER
                else -> BrutalistTheme.FG_WHITE
            }
            val borderColor = when (urgency) {
                com.javiblzq.assistant.data.QuickTask.URGENCY_HIGH -> BrutalistTheme.ACCENT_RED
                com.javiblzq.assistant.data.QuickTask.URGENCY_MED -> BrutalistTheme.ACCENT_AMBER
                else -> BrutalistTheme.BORDER
            }
            val fillColor = when (urgency) {
                com.javiblzq.assistant.data.QuickTask.URGENCY_HIGH -> 0xFF180808.toInt()
                com.javiblzq.assistant.data.QuickTask.URGENCY_MED -> 0xFF171206.toInt()
                else -> BrutalistTheme.BG_BLACK
            }
            val prefix = if (urgency == com.javiblzq.assistant.data.QuickTask.URGENCY_HIGH) "[!]" else "[ ]"

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = borderDrawable(borderColor, fillColor)
                setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                setOnClickListener { onToggleTask(t.id) }
            }

            val textView = makeText(12f, accentColor, compact = true).apply {
                text = "$prefix ${t.text}"
            }
            row.addView(textView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

            if (t.dueDate.isNotBlank()) {
                val dateColor = if (urgency == com.javiblzq.assistant.data.QuickTask.URGENCY_LOW) {
                    BrutalistTheme.FG_MUTED
                } else {
                    accentColor
                }
                val dateView = makeText(10f, dateColor, mono = true).apply {
                    text = t.dueDate
                    maxLines = 1
                }
                row.addView(dateView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    leftMargin = dp(8f)
                })
            }

            tasksList.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(4f)
            })
        }
    }

    private fun makePanel(title: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelDrawable()
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            addView(makeText(13f, BrutalistTheme.FG_WHITE, dots = true).apply {
                text = title
                setPadding(0, 0, 0, dp(4f))
            })
            addView(
                View(context).apply { setBackgroundColor(BrutalistTheme.BORDER) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)).apply { bottomMargin = dp(6f) }
            )
        }
    }

    private fun makeText(
        sizeSp: Float,
        color: Int,
        display: Boolean = false,
        compact: Boolean = false,
        mono: Boolean = false,
        dots: Boolean = false
    ): TextView {
        return TextView(context).apply {
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = when {
                dots -> BrutalistTheme.fontDots()
                display -> BrutalistTheme.fontDisplay()
                mono -> BrutalistTheme.fontMono()
                compact -> BrutalistTheme.fontCompact()
                else -> BrutalistTheme.fontCompact()
            }
            includeFontPadding = false
        }
    }

    private fun makeActionButton(label: String, onClick: () -> Unit): TextView {
        return makeText(11f, BrutalistTheme.FG_WHITE, mono = true).apply {
            text = label
            gravity = Gravity.CENTER
            background = borderDrawable(BrutalistTheme.BORDER_BRIGHT)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            setOnClickListener { onClick() }
        }
    }

    private fun panelDrawable(borderColor: Int = BrutalistTheme.BORDER): GradientDrawable {
        return GradientDrawable().apply {
            setColor(BrutalistTheme.BG_PANEL)
            setStroke(dp(1f), borderColor)
        }
    }

    private fun borderDrawable(
        borderColor: Int,
        fillColor: Int = BrutalistTheme.BG_BLACK
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            setStroke(dp(1f), borderColor)
        }
    }

    private fun dp(v: Float): Int = BrutalistTheme.dp(context, v)

    companion object {
        internal fun visibleTasks(tasks: List<com.javiblzq.assistant.data.QuickTask>): List<com.javiblzq.assistant.data.QuickTask> {
            return tasks.filter { !it.done }
        }

        internal fun formatFreshness(fetchedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
            if (fetchedAtMs <= 0L) return "SIN ACTUALIZAR"
            val elapsedSec = ((nowMs - fetchedAtMs) / 1000L).coerceAtLeast(0L)
            return when {
                elapsedSec < 60L -> "ACTUALIZADO AHORA"
                elapsedSec < 3600L -> "HACE ${elapsedSec / 60L} MIN"
                elapsedSec < 86_400L -> "HACE ${elapsedSec / 3600L} H"
                else -> "HACE >24 H"
            }
        }
    }
}
