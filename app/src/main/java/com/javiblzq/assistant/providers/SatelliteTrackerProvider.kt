package com.javiblzq.assistant.providers

import com.javiblzq.assistant.data.HardwareGnssSatellite
import com.javiblzq.assistant.data.SatelliteOverheadItem
import com.javiblzq.assistant.data.SatelliteSkySnapshot
import com.javiblzq.assistant.net.HttpClient
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class TleElement(
    val id: String,
    val rawName: String,
    val shortName: String,
    val constellation: String,
    val agency: String,
    val epochMs: Long,
    val inclinationRad: Double,
    val raanRad: Double,
    val eccentricity: Double,
    val argPerigeeRad: Double,
    val meanAnomalyRad: Double,
    val meanMotionRevPerDay: Double,
    val launchDate: String,
    val inclinationDeg: Float,
    val operator: String
)

data class TopocentricPosition(
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val altitudeKm: Int
)

class SatelliteTrackerProvider(
    private val cacheFile: File? = null,
    private val fetcher: (String) -> String? = { url ->
        val res = HttpClient.get(url, timeoutMs = 8000)
        if (res.isSuccess && res.body.isNotBlank()) res.body else null
    }
) {
    private val lock = Any()

    @Volatile
    private var cachedElements: List<TleElement> = emptyList()

    @Volatile
    private var almanacSource: String = SOURCE_SEED

    @Volatile
    private var lastCacheFetchedMs: Long = 0L

    init {
        loadInitialAlmanac()
    }

    private fun loadInitialAlmanac() {
        synchronized(lock) {
            val file = cacheFile
            if (file != null && file.exists()) {
                val loaded = readCacheFile(file)
                if (loaded != null && loaded.second.isNotEmpty()) {
                    lastCacheFetchedMs = loaded.first
                    cachedElements = supplementCatalog(loaded.second)
                    almanacSource = if (cachedElements.size > loaded.second.size) "CACHE + SEED" else SOURCE_LOCAL_CACHE
                    return
                }
            }
            cachedElements = parseTleCatalog(SEED_ALMANAC_TLE)
            almanacSource = SOURCE_SEED
        }
    }

    /**
     * Refreshes TLEs from CelesTrak if local cache is missing or older than [CACHE_TTL_MS] (12h).
     * Returns true if fresh TLEs were downloaded and persisted.
     */
    fun refreshAlmanacIfNeeded(nowMs: Long = System.currentTimeMillis(), force: Boolean = false): Boolean {
        val currentFetched = lastCacheFetchedMs
        if (!force && currentFetched > 0L && (nowMs - currentFetched) < CACHE_TTL_MS && cachedElements.isNotEmpty()) {
            return false
        }

        val combined = StringBuilder()
        var downloadedAny = false
        for (url in CELESTRAK_URLS) {
            val body = try {
                fetcher(url)
            } catch (_: Exception) {
                null
            }
            if (!body.isNullOrBlank() && body.contains("\n1 ") && body.contains("\n2 ")) {
                combined.append(body.trim()).append("\n")
                downloadedAny = true
            }
        }

        if (!downloadedAny) return false

        val parsed = parseTleCatalog(combined.toString())
        if (parsed.size < 6) return false

        synchronized(lock) {
            cachedElements = supplementCatalog(parsed)
            lastCacheFetchedMs = nowMs
            almanacSource = if (cachedElements.size > parsed.size) "CELESTRAK + SEED" else SOURCE_CELESTRAK
            cacheFile?.let { writeCacheFile(it, nowMs, combined.toString()) }
        }
        return true
    }

    /**
     * In-memory orbital propagation for the bounded catalog of non-GEO EU/US satellites,
     * optionally enriched with live hardware GNSS satellites from the tablet antenna.
     */
    fun computeOverhead(
        observerLatDeg: Double,
        observerLonDeg: Double,
        nowMs: Long = System.currentTimeMillis(),
        minElevationDeg: Float = MIN_ELEVATION_DEG,
        hardwareSatellites: List<HardwareGnssSatellite> = emptyList(),
        hardwareFixActive: Boolean = false
    ): SatelliteSkySnapshot {
        val elements = cachedElements.ifEmpty { parseTleCatalog(SEED_ALMANAC_TLE) }
        val hwByLabel = HashMap<String, HardwareGnssSatellite>()
        for (hw in hardwareSatellites) {
            hardwarePrnToLabel(hw.prn)?.let { label ->
                val prev = hwByLabel[label]
                if (prev == null || hw.snr > prev.snr) {
                    hwByLabel[label] = hw
                }
            }
        }

        val overhead = mutableListOf<SatelliteOverheadItem>()
        val matchedLabels = HashSet<String>()

        for (tle in elements) {
            if (isGeostationary(tle.meanMotionRevPerDay, null)) continue
            val pos = propagateToTopocentric(tle, observerLatDeg, observerLonDeg, nowMs)
            if (isGeostationary(tle.meanMotionRevPerDay, pos.altitudeKm)) continue
            if (pos.elevationDeg.isFinite() && pos.azimuthDeg.isFinite() && pos.elevationDeg > minElevationDeg) {
                val hw = hwByLabel[tle.shortName]
                if (hw != null) matchedLabels.add(tle.shortName)
                overhead.add(
                    SatelliteOverheadItem(
                        id = tle.id,
                        name = tle.shortName,
                        constellation = tle.constellation,
                        agency = tle.agency,
                        azimuthDeg = pos.azimuthDeg.toFloat(),
                        elevationDeg = pos.elevationDeg.toFloat(),
                        altitudeKm = pos.altitudeKm,
                        launchDate = tle.launchDate,
                        inclinationDeg = tle.inclinationDeg,
                        operator = tle.operator,
                        snr = hw?.snr ?: 0f,
                        usedInFix = hw?.usedInFix ?: false,
                        hardwareLocked = hw != null && (hw.snr > 0f || hw.usedInFix)
                    )
                )
            }
        }

        // Merge any hardware-tracked GPS/Galileo satellites above horizon not already in TLE list
        for (hw in hardwareSatellites) {
            if (hw.elevationDeg <= minElevationDeg) continue
            val label = hardwarePrnToLabel(hw.prn) ?: continue
            if (label in matchedLabels) continue
            val isEu = label.startsWith("E")
            overhead.add(
                SatelliteOverheadItem(
                    id = "HW-${hw.prn}",
                    name = label,
                    constellation = if (isEu) "GALILEO" else "GPS",
                    agency = if (isEu) AGENCY_EU else AGENCY_US,
                    azimuthDeg = hw.azimuthDeg,
                    elevationDeg = hw.elevationDeg,
                    altitudeKm = if (isEu) 23222 else 20200,
                    // A PRN can be reassigned; only use metadata from a matching orbital record.
                    launchDate = elements.firstOrNull { it.shortName == label }?.launchDate ?: "DESCONOCIDO",
                    inclinationDeg = elements.firstOrNull { it.shortName == label }?.inclinationDeg ?: Float.NaN,
                    operator = if (isEu) "ESA / EUSPA (UE)" else "USSF / SPACE FORCE (EE.UU.)",
                    snr = hw.snr,
                    usedInFix = hw.usedInFix,
                    hardwareLocked = hw.snr > 0f || hw.usedInFix
                )
            )
            matchedLabels.add(label)
        }

        overhead.sortByDescending { it.elevationDeg }
        // Keep the highest visible objects per commercial constellation; counters match plotted points.
        val shownPerConstellation = HashMap<String, Int>()
        val visible = overhead.filter { sat ->
            if (sat.constellation !in COMMERCIAL_CONSTELLATIONS) true else {
                val count = shownPerConstellation[sat.constellation] ?: 0
                shownPerConstellation[sat.constellation] = count + 1
                count < 12
            }
        }
        val euCount = visible.count { it.agency == AGENCY_EU }
        val usCount = visible.count { it.agency == AGENCY_US }
        val activeHwCount = hardwareSatellites.count { it.snr > 0f || it.usedInFix }

        return SatelliteSkySnapshot(
            satellites = visible,
            euCount = euCount,
            usCount = usCount,
            almanacSource = almanacSource,
            calculatedAtMs = nowMs,
            hardwareFixActive = hardwareFixActive,
            hardwareSatCount = activeHwCount,
            observerLat = observerLatDeg,
            observerLon = observerLonDeg
        )
    }

    private fun readCacheFile(file: File): Pair<Long, List<TleElement>>? {
        return try {
            val text = file.readText(Charsets.UTF_8)
            var fetchedMs = file.lastModified()
            val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
            if (firstLine.startsWith(CACHE_HEADER_PREFIX)) {
                firstLine.removePrefix(CACHE_HEADER_PREFIX).trim().toLongOrNull()?.let { fetchedMs = it }
            }
            val parsed = parseTleCatalog(text)
            if (parsed.isEmpty()) null else fetchedMs to parsed
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCacheFile(file: File, fetchedMs: Long, rawTles: String) {
        try {
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            val content = "$CACHE_HEADER_PREFIX$fetchedMs\n$rawTles"
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private val COMMERCIAL_CONSTELLATIONS = setOf("ICEYE", "ONEWEB", "STARLINK", "IRIDIUM", "PLANET", "SPIRE")
        const val AGENCY_EU = "EU"
        const val AGENCY_US = "US"
        const val SOURCE_SEED = "ALMANAQUE SEED"
        const val SOURCE_LOCAL_CACHE = "CACHE LOCAL"
        const val SOURCE_CELESTRAK = "CELESTRAK LIVE"
        const val CACHE_FILE_NAME = "satellite_tle_cache.txt"
        const val MIN_ELEVATION_DEG = 5.0f
        const val CACHE_TTL_MS = 12L * 3_600_000L
        const val MIN_NON_GEO_MEAN_MOTION = 1.5
        const val MAX_NON_GEO_ALTITUDE_KM = 30_000

        fun isGeostationary(meanMotionRevPerDay: Double, altitudeKm: Int?): Boolean {
            if (meanMotionRevPerDay < MIN_NON_GEO_MEAN_MOTION) return true
            if (altitudeKm != null && altitudeKm > MAX_NON_GEO_ALTITUDE_KM) return true
            return false
        }

        internal fun hardwarePrnToLabel(prn: Int): String? = when (prn) {
            in 1..32 -> String.format(Locale.US, "G%02d", prn)
            in 301..336 -> String.format(Locale.US, "E%02d", prn - 300)
            else -> null
        }

        private const val CACHE_HEADER_PREFIX = "# FETCHED_AT_MS="

        // Older installations and partial network responses retain the bundled missions.
        // Fresh records win by NORAD ID; offline records keep their actual epoch.
        private fun supplementCatalog(primary: List<TleElement>): List<TleElement> =
            limitCommercialCatalog((primary + parseTleCatalog(SEED_ALMANAC_TLE)).distinctBy { it.id })

        private fun limitCommercialCatalog(elements: List<TleElement>): List<TleElement> =
            elements.groupBy { it.constellation }.values.flatMap { group ->
                if (group.first().constellation !in COMMERCIAL_CONSTELLATIONS || group.size <= 64) group
                else {
                    val planes = group.sortedBy { it.raanRad }
                    (0 until 64).map { planes[it * planes.size / 64] }
                }
            }

        // WGS84 & EGM96 orbital constants
        private const val MU_KM3_S2 = 398600.4418
        private const val EARTH_RADIUS_KM = 6378.137
        private const val WGS84_F = 1.0 / 298.257223563
        private const val WGS84_E2 = 2.0 * WGS84_F - WGS84_F * WGS84_F
        private const val J2 = 1.08262668e-3
        private const val TWO_PI = 2.0 * PI
        private const val DEG_TO_RAD = PI / 180.0
        private const val RAD_TO_DEG = 180.0 / PI

        val CELESTRAK_URLS = listOf(
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=galileo&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=gps-ops&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?NAME=SENTINEL&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=resource&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=weather&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=science&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?NAME=ICEYE&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=oneweb&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=iridium-NEXT&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?NAME=FLOCK&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?NAME=LEMUR&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?CATNR=69712&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?CATNR=63842&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?CATNR=64353&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?CATNR=58712&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?CATNR=66259&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?CATNR=60602&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?CATNR=69420&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?CATNR=48368&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?CATNR=68718&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?CATNR=61587&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?CATNR=64463&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?CATNR=64274&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?CATNR=25338&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?CATNR=28654&FORMAT=tle",
            "https://celestrak.org/NORAD/elements/gp.php?CATNR=33591&FORMAT=tle"
        )

        /**
         * Parses a multi-satellite TLE text catalog and keeps only recognized EU/US satellites,
         * deduplicated by NORAD catalog ID.
         */
        fun parseTleCatalog(rawText: String): List<TleElement> {
            val lines = rawText.lineSequence()
                .map { it.trimEnd() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .toList()

            val byId = LinkedHashMap<String, TleElement>()
            var i = 0
            while (i + 2 < lines.size) {
                val l0 = lines[i].trim()
                val l1 = lines[i + 1].trim()
                val l2 = lines[i + 2].trim()

                if (l1.startsWith("1 ") && l2.startsWith("2 ") && !l0.startsWith("1 ") && !l0.startsWith("2 ")) {
                    parseSingleTle(l0, l1, l2)?.let { elem ->
                        byId[elem.id] = elem
                    }
                    i += 3
                } else {
                    i += 1
                }
            }
            return limitCommercialCatalog(byId.values.toList())
        }

        fun parseSingleTle(nameLine: String, line1: String, line2: String): TleElement? {
            if (line1.length < 32 || line2.length < 63) return null
            val classification = classifySatellite(nameLine) ?: return null

            val satNum = line1.substring(2, 7).trim()
            val epochYear2 = line1.substring(18, 20).trim().toIntOrNull() ?: return null
            val epochDay = line1.substring(20, 32).trim().toDoubleOrNull() ?: return null
            val epochYear = if (epochYear2 < 57) 2000 + epochYear2 else 1900 + epochYear2

            val incDeg = line2.substring(8, 16).trim().toDoubleOrNull() ?: return null
            val raanDeg = line2.substring(17, 25).trim().toDoubleOrNull() ?: return null
            val eccDigits = line2.substring(26, 33).trim()
            val ecc = ("0.$eccDigits").toDoubleOrNull() ?: return null
            val argpDeg = line2.substring(34, 42).trim().toDoubleOrNull() ?: return null
            val meanAnomDeg = line2.substring(43, 51).trim().toDoubleOrNull() ?: return null
            val meanMotion = line2.substring(52, 63).trim().toDoubleOrNull() ?: return null
            if (meanMotion <= 0.0 || ecc < 0.0 || ecc >= 1.0) return null
            if (isGeostationary(meanMotion, null)) return null

            val epochMs = tleEpochToUnixMs(epochYear, epochDay)
            return TleElement(
                id = satNum,
                rawName = nameLine.trim(),
                shortName = classification.shortName,
                constellation = classification.constellation,
                agency = classification.agency,
                epochMs = epochMs,
                inclinationRad = incDeg * DEG_TO_RAD,
                raanRad = raanDeg * DEG_TO_RAD,
                eccentricity = ecc,
                argPerigeeRad = argpDeg * DEG_TO_RAD,
                meanAnomalyRad = meanAnomDeg * DEG_TO_RAD,
                meanMotionRevPerDay = meanMotion,
                launchDate = launchDateFor(line1),
                inclinationDeg = incDeg.toFloat(),
                operator = operatorFor(nameLine, classification.constellation)
            )
        }

        internal data class SatClassification(
            val shortName: String,
            val constellation: String,
            val agency: String
        )

        internal fun classifySatellite(rawName: String): SatClassification? {
            val u = rawName.trim().uppercase(Locale.US)
            return when {
                u.contains("GALILEO") || u.startsWith("GSAT") -> {
                    val galNum = Regex("""GALILEO[- ]?(\d+)""").find(u)?.groupValues?.get(1)?.toIntOrNull()
                    val gsatNum = Regex("""GSAT0*(\d+)""").find(u)?.groupValues?.get(1)?.toIntOrNull()
                    val label = when {
                        galNum != null -> String.format(Locale.US, "E%02d", galNum)
                        gsatNum != null -> "E${gsatNum % 100}"
                        else -> "GAL"
                    }
                    SatClassification(label, "GALILEO", AGENCY_EU)
                }
                u.contains("SENTINEL") -> {
                    val code = Regex("""SENTINEL[- ]?([0-9][A-Z])""").find(u)?.groupValues?.get(1)
                    val label = if (code != null) "SNT$code" else "SNT"
                    SatClassification(label, "ESA", AGENCY_EU)
                }
                u.contains("GPS") || u.contains("NAVSTAR") -> {
                    val prn = Regex("""PRN\s*(\d+)""").find(u)?.groupValues?.get(1)?.toIntOrNull()
                    val sv = Regex("""SV(\d+)""").find(u)?.groupValues?.get(1)?.toIntOrNull()
                    val nav = Regex("""NAVSTAR\s*(\d+)""").find(u)?.groupValues?.get(1)?.toIntOrNull()
                    val label = when {
                        prn != null -> String.format(Locale.US, "G%02d", prn)
                        sv != null -> String.format(Locale.US, "G%02d", sv)
                        nav != null -> String.format(Locale.US, "G%02d", nav % 100)
                        else -> "GPS"
                    }
                    SatClassification(label, "GPS", AGENCY_US)
                }
                u == "ISS (ZARYA)" || u.startsWith("ISS ") || u == "ISS" -> {
                    SatClassification("ISS", "ISS", AGENCY_US)
                }
                u.contains("NOAA") -> {
                    val num = Regex("""NOAA[- ]?(\d+)""").find(u)?.groupValues?.get(1)
                    val label = if (num != null) "N$num" else "NOAA"
                    SatClassification(label, "NOAA", AGENCY_US)
                }
                u.contains("HST") || u.contains("HUBBLE") -> {
                    SatClassification("HST", "NASA", AGENCY_US)
                }
                u.startsWith("ICEYE") -> SatClassification(u, "ICEYE", AGENCY_EU)
                u.startsWith("ONEWEB") -> SatClassification(u, "ONEWEB", AGENCY_EU)
                u.startsWith("STARLINK") -> SatClassification(u, "STARLINK", AGENCY_US)
                u.startsWith("IRIDIUM") -> SatClassification(u, "IRIDIUM", AGENCY_US)
                u.startsWith("FLOCK") -> SatClassification(u, "PLANET", AGENCY_US)
                u.startsWith("LEMUR") -> SatClassification(u, "SPIRE", AGENCY_US)
                u.startsWith("METOP-") -> SatClassification(u, "EUMETSAT", AGENCY_EU)
                u.startsWith("CRYOSAT") || u == "SMOS" || u.startsWith("SWARM") ->
                    SatClassification(u, "ESA", AGENCY_EU)
                u.startsWith("LANDSAT") || u == "TERRA" || u == "AQUA" || u == "SUOMI NPP" ->
                    SatClassification(u, "NASA", AGENCY_US)
                else -> null
            }
        }

        // Launch identity is the International Designator, never the orbital epoch.
        internal fun launchDateFor(line1: String): String {
            val launch = line1.substring(9, 14)
            val known = mapOf(
                "11060" to "2011-10-21", "15017" to "2015-03-27", "15045" to "2015-09-11",
                "15079" to "2015-12-17", "16030" to "2016-05-24", "16069" to "2016-11-17",
                "17079" to "2017-12-12", "18060" to "2018-07-25", "21116" to "2021-12-05",
                "97035" to "1997-07-23", "98067" to "1998-11-20", "90037" to "1990-04-24",
                "15028" to "2015-06-23", "17013" to "2017-03-07", "14016" to "2014-04-03",
                "16011" to "2016-02-16", "18039" to "2018-04-25", "17064" to "2017-10-13",
                "20086" to "2020-11-21", "12049" to "2012-09-17", "18087" to "2018-11-07",
                "10013" to "2010-04-08", "09059" to "2009-11-02", "13067" to "2013-11-22",
                "98030" to "1998-05-13", "05018" to "2005-05-20", "09005" to "2009-02-06",
                "17073" to "2017-11-18", "22150" to "2022-11-10", "13008" to "2013-02-11",
                "21088" to "2021-09-27", "99068" to "1999-12-18", "02022" to "2002-05-04",
                "11061" to "2011-10-28"
            )
            known[launch]?.let { return it }
            val yy = line1.substring(9, 11).trim().toIntOrNull() ?: return "DESCONOCIDO"
            return (if (yy < 57) 2000 + yy else 1900 + yy).toString()
        }

        private fun operatorFor(name: String, constellation: String): String = when {
            constellation == "ICEYE" -> "ICEYE (UE / FINLANDIA)"
            constellation == "ONEWEB" -> "EUTELSAT ONEWEB (UE)"
            constellation == "STARLINK" -> "SPACEX / STARLINK (EE.UU.)"
            constellation == "IRIDIUM" -> "IRIDIUM COMM. (EE.UU.)"
            constellation == "PLANET" -> "PLANET LABS (EE.UU.)"
            constellation == "SPIRE" -> "SPIRE GLOBAL (EE.UU.)"
            constellation == "GALILEO" -> "ESA / EUSPA (UE)"
            constellation == "GPS" -> "USSF / SPACE FORCE (EE.UU.)"
            constellation == "ISS" -> "NASA / ESA (ISS)"
            constellation == "NOAA" || name.uppercase(Locale.US).contains("SUOMI") -> "NOAA / NASA (EE.UU.)"
            name.uppercase(Locale.US).contains("METOP") -> "EUMETSAT (UE)"
            name.uppercase(Locale.US).contains("SENTINEL") -> "ESA / COPERNICUS (UE)"
            name.uppercase(Locale.US).contains("LANDSAT") -> "USGS / NASA (EE.UU.)"
            name == "HST" || name.uppercase(Locale.US).contains("HUBBLE") -> "NASA / ESA (HST)"
            constellation == "ESA" -> "ESA (UE)"
            else -> "NASA (EE.UU.)"
        }

        internal fun tleEpochToUnixMs(year: Int, dayOfYear: Double): Long {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val offsetMs = ((dayOfYear - 1.0) * 86_400_000.0).toLong()
            return cal.timeInMillis + offsetMs
        }

        /**
         * Propagates a TLE element using a Keplerian + J2 secular perturbation model,
         * transforms PQW -> ECI -> ECEF (via GMST) -> Topocentric ENU, and returns
         * Azimuth (0..360 deg, 0=N, 90=E), Elevation (-90..+90 deg), and Altitude (km).
         */
        fun propagateToTopocentric(
            tle: TleElement,
            observerLatDeg: Double,
            observerLonDeg: Double,
            nowMs: Long,
            observerAltKm: Double = 0.65
        ): TopocentricPosition {
            val dtSec = (nowMs - tle.epochMs) / 1000.0

            // 1. Mean motion (rad/s) and semi-major axis (km)
            val n0 = tle.meanMotionRevPerDay * TWO_PI / 86400.0
            val a = cbrt(MU_KM3_S2 / (n0 * n0))
            val e = tle.eccentricity
            val oneMinusE2 = (1.0 - e * e).coerceAtLeast(1e-8)
            val p = a * oneMinusE2
            val inc = tle.inclinationRad
            val cosI = cos(inc)
            val sinI = sin(inc)

            // 2. Secular J2 drift rates (rad/s)
            val j2Factor = 0.75 * J2 * (EARTH_RADIUS_KM / p) * (EARTH_RADIUS_KM / p) * n0
            val raanDot = -2.0 * j2Factor * cosI
            val argpDot = j2Factor * (5.0 * cosI * cosI - 1.0)
            val meanAnomDotJ2 = j2Factor * sqrt(oneMinusE2) * (3.0 * cosI * cosI - 1.0)

            val raan = tle.raanRad + raanDot * dtSec
            val argp = tle.argPerigeeRad + argpDot * dtSec
            val meanAnom = normalizeRadians(tle.meanAnomalyRad + (n0 + meanAnomDotJ2) * dtSec)

            // 3. Solve Kepler's equation M = E - e*sin(E) by Newton-Raphson
            var eccAnom = meanAnom + e * sin(meanAnom)
            for (iter in 0 until 8) {
                val fVal = eccAnom - e * sin(eccAnom) - meanAnom
                val fPrime = 1.0 - e * cos(eccAnom)
                val delta = fVal / fPrime
                eccAnom -= delta
                if (abs(delta) < 1e-10) break
            }

            // 4. Perifocal coordinates (km)
            val cosE = cos(eccAnom)
            val sinE = sin(eccAnom)
            val rKm = a * (1.0 - e * cosE)
            val xPqw = a * (cosE - e)
            val yPqw = a * sqrt(oneMinusE2) * sinE
            val altKm = (rKm - EARTH_RADIUS_KM).roundToInt().coerceAtLeast(0)

            // 5. Rotate PQW -> ECI
            val cosO = cos(raan)
            val sinO = sin(raan)
            val cosW = cos(argp)
            val sinW = sin(argp)

            val r11 = cosO * cosW - sinO * sinW * cosI
            val r12 = -cosO * sinW - sinO * cosW * cosI
            val r21 = sinO * cosW + cosO * sinW * cosI
            val r22 = -sinO * sinW + cosO * cosW * cosI
            val r31 = sinW * sinI
            val r32 = cosW * sinI

            val xEci = r11 * xPqw + r12 * yPqw
            val yEci = r21 * xPqw + r22 * yPqw
            val zEci = r31 * xPqw + r32 * yPqw

            // 6. Greenwich Mean Sidereal Time (GMST) & ECI -> ECEF
            val gmstRad = computeGmstRad(nowMs)
            val cosG = cos(gmstRad)
            val sinG = sin(gmstRad)
            val xEcef = xEci * cosG + yEci * sinG
            val yEcef = -xEci * sinG + yEci * cosG
            val zEcef = zEci

            // 7. Observer WGS84 geodetic -> ECEF
            val latRad = observerLatDeg * DEG_TO_RAD
            val lonRad = observerLonDeg * DEG_TO_RAD
            val sinLat = sin(latRad)
            val cosLat = cos(latRad)
            val sinLon = sin(lonRad)
            val cosLon = cos(lonRad)

            val primeVertical = EARTH_RADIUS_KM / sqrt(1.0 - WGS84_E2 * sinLat * sinLat)
            val obsX = (primeVertical + observerAltKm) * cosLat * cosLon
            val obsY = (primeVertical + observerAltKm) * cosLat * sinLon
            val obsZ = (primeVertical * (1.0 - WGS84_E2) + observerAltKm) * sinLat

            // 8. Relative vector -> Topocentric ENU
            val dx = xEcef - obsX
            val dy = yEcef - obsY
            val dz = zEcef - obsZ

            val east = -sinLon * dx + cosLon * dy
            val north = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz
            val up = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz

            val horiz = sqrt(east * east + north * north)
            val elDeg = atan2(up, horiz) * RAD_TO_DEG
            val azDeg = (atan2(east, north) * RAD_TO_DEG + 360.0) % 360.0

            return TopocentricPosition(
                azimuthDeg = azDeg,
                elevationDeg = elDeg,
                altitudeKm = altKm
            )
        }

        internal fun computeGmstRad(unixMs: Long): Double {
            val jd = unixMs / 86_400_000.0 + 2440587.5
            val d = jd - 2451545.0
            val t = d / 36525.0
            var gmstDeg = 280.46061837 + 360.98564736629 * d + 0.000387933 * t * t - (t * t * t) / 38710000.0
            gmstDeg = ((gmstDeg % 360.0) + 360.0) % 360.0
            return gmstDeg * DEG_TO_RAD
        }

        private fun normalizeRadians(rad: Double): Double {
            var r = rad % TWO_PI
            if (r < 0.0) r += TWO_PI
            return r
        }

        /**
         * Embedded Seed Almanac (Galileo EU 3 planes, GPS US 6 planes, ISS, ESA Sentinels, NOAA-20, Hubble).
         * Guarantees 100% immediate operation offline from second 0; replaced automatically by fresh
         * CelesTrak TLEs whenever network is available.
         */
        val SEED_ALMANAC_TLE: String = """
GSAT0101 (GALILEO 1)
1 37846U 11060A   26330.00000000  .00000010  00000-0  00000-0 0  9991
2 37846  55.1000  15.0000 0003000  30.0000  10.0000  1.70475000 94001
GSAT0102 (GALILEO 2)
1 37847U 11060B   26330.00000000  .00000010  00000-0  00000-0 0  9992
2 37847  55.1000  15.0000 0003000  35.0000  70.0000  1.70475000 94002
GSAT0203 (GALILEO 7)
1 40544U 15017A   26330.00000000  .00000010  00000-0  00000-0 0  9993
2 40544  55.2000  15.0000 0003000  40.0000 130.0000  1.70475000 72003
GSAT0205 (GALILEO 9)
1 40889U 15045A   26330.00000000  .00000010  00000-0  00000-0 0  9994
2 40889  55.2000  15.0000 0003000  45.0000 190.0000  1.70475000 71004
GSAT0207 (GALILEO 11)
1 41174U 15079A   26330.00000000  .00000010  00000-0  00000-0 0  9995
2 41174  55.3000  15.0000 0003000  50.0000 250.0000  1.70475000 69005
GSAT0209 (GALILEO 13)
1 41549U 16030A   26330.00000000  .00000010  00000-0  00000-0 0  9996
2 41549  55.3000  15.0000 0003000  55.0000 310.0000  1.70475000 66006
GSAT0210 (GALILEO 14)
1 41550U 16030B   26330.00000000  .00000010  00000-0  00000-0 0  9997
2 41550  55.9000 135.0000 0003000  20.0000  25.0000  1.70475000 66007
GSAT0211 (GALILEO 15)
1 41859U 16069A   26330.00000000  .00000010  00000-0  00000-0 0  9998
2 41859  55.9000 135.0000 0003000  25.0000  85.0000  1.70475000 63008
GSAT0212 (GALILEO 16)
1 41860U 16069B   26330.00000000  .00000010  00000-0  00000-0 0  9999
2 41860  56.0000 135.0000 0003000  30.0000 145.0000  1.70475000 63009
GSAT0214 (GALILEO 18)
1 41862U 16069D   26330.00000000  .00000010  00000-0  00000-0 0  9991
2 41862  56.0000 135.0000 0003000  35.0000 205.0000  1.70475000 63010
GSAT0215 (GALILEO 19)
1 43055U 17079A   26330.00000000  .00000010  00000-0  00000-0 0  9992
2 43055  56.1000 135.0000 0003000  40.0000 265.0000  1.70475000 57011
GSAT0218 (GALILEO 22)
1 43058U 17079D   26330.00000000  .00000010  00000-0  00000-0 0  9993
2 43058  56.1000 135.0000 0003000  45.0000 325.0000  1.70475000 57012
GSAT0219 (GALILEO 23)
1 43564U 18060A   26330.00000000  .00000010  00000-0  00000-0 0  9994
2 43564  56.2000 255.0000 0003000  10.0000  40.0000  1.70475000 52013
GSAT0220 (GALILEO 24)
1 43565U 18060B   26330.00000000  .00000010  00000-0  00000-0 0  9995
2 43565  56.2000 255.0000 0003000  15.0000 100.0000  1.70475000 52014
GSAT0221 (GALILEO 25)
1 43566U 18060C   26330.00000000  .00000010  00000-0  00000-0 0  9996
2 43566  56.3000 255.0000 0003000  20.0000 160.0000  1.70475000 52015
GSAT0222 (GALILEO 26)
1 43567U 18060D   26330.00000000  .00000010  00000-0  00000-0 0  9997
2 43567  56.3000 255.0000 0003000  25.0000 220.0000  1.70475000 52016
GSAT0223 (GALILEO 27)
1 49809U 21116A   26330.00000000  .00000010  00000-0  00000-0 0  9998
2 49809  56.4000 255.0000 0003000  30.0000 280.0000  1.70475000 31017
GSAT0224 (GALILEO 28)
1 49810U 21116B   26330.00000000  .00000010  00000-0  00000-0 0  9999
2 49810  56.4000 255.0000 0003000  35.0000 340.0000  1.70475000 31018
GPS BIIR-2  (PRN 13)
1 24876U 97035A   26330.00000000 -.00000020  00000-0  00000-0 0  9991
2 24876  55.1000  20.0000 0050000  45.0000  15.0000  2.0056300021501
GPS BIIR-11 (PRN 19)
1 28190U 04009A   26330.00000000 -.00000020  00000-0  00000-0 0  9992
2 28190  55.2000  20.0000 0045000  50.0000 135.0000  2.0056300016502
GPS BIIF-1  (PRN 25)
1 36585U 10022A   26330.00000000 -.00000020  00000-0  00000-0 0  9993
2 36585  55.3000  20.0000 0060000  55.0000 255.0000  2.0056300012003
GPS BIIF-2  (PRN 01)
1 37753U 11036A   26330.00000000 -.00000020  00000-0  00000-0 0  9994
2 37753  55.0000  80.0000 0050000  30.0000  55.0000  2.0056300011204
GPS BIIF-3  (PRN 24)
1 38833U 12053A   26330.00000000 -.00000020  00000-0  00000-0 0  9995
2 38833  55.1000  80.0000 0048000  35.0000 175.0000  2.0056300010305
GPS BIIF-4  (PRN 27)
1 39166U 13023A   26330.00000000 -.00000020  00000-0  00000-0 0  9996
2 39166  55.2000  80.0000 0052000  40.0000 295.0000  2.00563000 98006
GPS BIIF-5  (PRN 30)
1 39533U 14008A   26330.00000000 -.00000020  00000-0  00000-0 0  9997
2 39533  55.4000 140.0000 0042000  60.0000  20.0000  2.00563000 92007
GPS BIIF-6  (PRN 06)
1 39741U 14026A   26330.00000000 -.00000020  00000-0  00000-0 0  9998
2 39741  55.5000 140.0000 0040000  65.0000 140.0000  2.00563000 91008
GPS BIIF-7  (PRN 09)
1 40105U 14045A   26330.00000000 -.00000020  00000-0  00000-0 0  9999
2 40105  55.6000 140.0000 0038000  70.0000 260.0000  2.00563000 89009
GPS BIIF-8  (PRN 03)
1 40294U 14068A   26330.00000000 -.00000020  00000-0  00000-0 0  9991
2 40294  55.1000 200.0000 0041000  25.0000  65.0000  2.00563000 87010
GPS BIIF-9  (PRN 26)
1 40534U 15013A   26330.00000000 -.00000020  00000-0  00000-0 0  9992
2 40534  55.2000 200.0000 0043000  30.0000 185.0000  2.00563000 85011
GPS BIIF-10 (PRN 08)
1 40730U 15033A   26330.00000000 -.00000020  00000-0  00000-0 0  9993
2 40730  55.3000 200.0000 0044000  35.0000 305.0000  2.00563000 83012
GPS BIIF-11 (PRN 10)
1 41019U 15062A   26330.00000000 -.00000020  00000-0  00000-0 0  9994
2 41019  55.4000 260.0000 0039000  45.0000  35.0000  2.00563000 81013
GPS BIIF-12 (PRN 32)
1 41328U 16007A   26330.00000000 -.00000020  00000-0  00000-0 0  9995
2 41328  55.5000 260.0000 0037000  50.0000 155.0000  2.00563000 79014
GPS III SV01 (PRN 04)
1 43873U 18109A   26330.00000000 -.00000020  00000-0  00000-0 0  9996
2 43873  55.2000 260.0000 0025000  55.0000 275.0000  2.00563000 58015
GPS III SV03 (PRN 23)
1 45854U 20041A   26330.00000000 -.00000020  00000-0  00000-0 0  9997
2 45854  55.1000 320.0000 0024000  40.0000  80.0000  2.00563000 47016
GPS III SV04 (PRN 14)
1 46826U 20078A   26330.00000000 -.00000020  00000-0  00000-0 0  9998
2 46826  55.2000 320.0000 0023000  45.0000 200.0000  2.00563000 44017
GPS III SV06 (PRN 28)
1 55268U 23009A   26330.00000000 -.00000020  00000-0  00000-0 0  9999
2 55268  55.3000 320.0000 0021000  50.0000 320.0000  2.00563000 28018
ISS (ZARYA)
1 25544U 98067A   26330.00000000  .00016717  00000-0  29800-3 0  9990
2 25544  51.6400 120.0000 0005000  90.0000  45.0000 15.5010000054001
TERRA
1 25994U 99068A   26330.31559294  .00000189  00000+0  47392-4 0  9999
2 25994  97.9247  13.2573 0003605  84.5954 339.3567 14.61205095433288
AQUA
1 27424U 02022A   26330.23324286  .00000332  00000+0  75700-4 0  9999
2 27424  98.4505 302.9533 0001445 103.5811 313.1617 14.62295315306953
SMOS
1 36036U 09059A   26330.25477459  .00000155  00000+0  63237-4 0  9993
2 36036  98.4390 155.2385 0001077  88.0304 272.1007 14.39758405896772
LANDSAT 8
1 39084U 13008A   26330.29148298  .00000177  00000+0  49398-4 0  9994
2 39084  98.2135  38.3431 0001177  92.7570 267.3763 14.57118798721502
SENTINEL-1A
1 39634U 14016A   26330.21465328  .00000730  00000+0  15924-3 0  9995
2 39634  98.1580 335.1867 0001361  90.8290 269.3066 14.60695076673653
SENTINEL-2A
1 40697U 15028A   26330.31246541  .00000122  00000+0  63350-4 0  9997
2 40697  98.5666  42.7844 0001211  93.4520 266.6801 14.30819936596918
SENTINEL-3A
1 41335U 16011A   26330.28666891  .00000060  00000+0  42773-4 0  9997
2 41335  98.6283  35.3332 0001052  83.0436 277.0864 14.26738473561174
SENTINEL-2B
1 42063U 17013A   26330.30544208  .00000036  00000+0  30389-4 0  9992
2 42063  98.5713  42.7691 0001144  91.0992 269.0322 14.30815987507821
SENTINEL-5P
1 42969U 17064A   26330.24925812  .00000012  00000+0  26612-4 0  9998
2 42969  98.8117 271.3402 0001134 101.6081 258.5222 14.19502630472551
SENTINEL-3B
1 43437U 18039A   26330.18968622  .00000056  00000+0  41337-4 0  9990
2 43437  98.6287  35.3876 0001133 125.8389 234.2897 14.26734018447229
SENTINEL-6A
1 46984U 20086A   26330.24918680 -.00000052  00000+0  39047-4 0  9993
2 46984  66.0383 158.4440 0007661 269.9915  90.0222 12.80931184281087
LANDSAT 9
1 49260U 21088A   26330.18846195  .00000147  00000+0  42689-4 0  9990
2 49260  98.2144  38.2494 0001053  88.0132 272.1187 14.57100447274590
SENTINEL-2C
1 60989U 24157A   26330.20051219  .00000061  00000+0  39896-4 0  9990
2 60989  98.5719  42.6616 0001019 109.5092 250.6201 14.30816798116152
SENTINEL-6B
1 66514U 25264A   26330.24954452 -.00000052  00000+0  40443-4 0  9996
2 66514  66.0385 158.5696 0007544 269.1187  90.8964 12.80931064 47928
SUOMI NPP
1 37849U 11061A   26330.24540434  .00000037  00000+0  38697-4 0  9996
2 37849  98.8138 270.1252 0001222 183.8972 176.2195 14.19545668781437
METOP-B
1 38771U 12049A   26330.29809574  .00000060  00000+0  47189-4 0  9998
2 38771  98.6293  18.3873 0001605 212.4729 147.6350 14.21475684736299
NOAA 20 (JPSS-1)
1 43013U 17073A   26330.18891418  .00000030  00000+0  35124-4 0  9994
2 43013  98.7362 268.2913 0001499 118.0190 242.1138 14.19551360467454
METOP-C
1 43689U 18087A   26330.24232149  .00000064  00000+0  49280-4 0  9991
2 43689  98.7228  27.3769 0001755 106.3267 253.8104 14.21476627417853
NOAA 21 (JPSS-2)
1 54234U 22150A   26330.28972646  .00000021  00000+0  30517-4 0  9998
2 54234  98.7225 266.4461 0002824  88.8863 271.2637 14.19560885209544
METOP-SGA1
1 65159U 25172A   26330.13599515  .00000021  00000+0  29611-4 0  9990
2 65159  98.7343  27.4323 0001274  95.7852 264.3471 14.21477166 66797
HST
1 20580U 90037B   26330.26264168  .00004022  00000+0  11752-3 0  9999
2 20580  28.4739  51.4559 0002420 144.3239 215.7519 15.32422762813447
CRYOSAT 2
1 36508U 10013A   26330.25034340  .00000047  00000+0  10847-4 0  9996
2 36508  92.0216 316.5626 0002693  88.9849 271.1667 14.51909789881593
SWARM B
1 39451U 13067A   26330.31472048  .00002079  00000+0  71644-4 0  9995
2 39451  87.7394 293.0133 0003289  96.0993 264.0631 15.29420768708013
SWARM A
1 39452U 13067B   26330.22733429  .00005740  00000+0  94387-4 0  9996
2 39452  87.3319 356.0490 0002814  87.5405 272.6179 15.52105284731364
SWARM C
1 39453U 13067C   26330.22724347  .00005779  00000+0  95023-4 0  9996
2 39453  87.3318 354.6327 0002820  88.0558 272.1026 15.52107219731321
STARLINK-38043
1 69712U 26147J   26330.06969574  .00007803  00000+0  24377-3 0  9998
2 69712  97.2834   5.4463 0001182 102.4546 257.6829 15.33445199 24432
STARLINK-34026
1 63842U 25093G   26330.26232195  .00008425  00000+0  31528-3 0  9996
2 63842  42.9959  40.9006 0002575 164.9863 195.1065 15.27586952 87752
STARLINK-34431
1 64353U 25126K   26330.25958139  .00010838  00000+0  37115-3 0  9992
2 64353  69.9918  84.1382 0002539 161.7099 198.4168 15.30427299 80134
STARLINK-31029
1 58712U 24002H   26329.40938855  .00000598  00000+0  28834-4 0  9995
2 58712  53.1596  95.3082 0000873  96.3511 263.7591 15.34386352161982
STARLINK-35529
1 66259U 25245AB  26329.53413532  .00077783  00000+0  17825-2 0  9990
2 66259  53.1553 148.6929 0004666 233.3587 126.6990 15.42088830 61249
STARLINK-31983
1 60602U 24150V   26330.28616890  .00009860  00000+0  30264-3 0  9990
2 60602  53.1574 165.5059 0001286  95.6588 264.4561 15.34393452128109
STARLINK-37537
1 69420U 26126A   26330.04260854  .00009205  00000+0  28712-3 0  9999
2 69420  97.2824 190.3573 0002252  97.2888 262.8611 15.33442524 27723
STARLINK-2623
1 48368U 21038R   26330.04410401  .00010930  00000+0  33382-3 0  9995
2 48368  53.1568 211.5734 0003559 132.4830 227.6474 15.34431709307273
STARLINK-36684
1 68718U 26081V   26330.22427118  .00012950  00000+0  39370-3 0  9999
2 68718  53.1586 253.7006 0001244 104.6433 255.4708 15.34403964 34866
STARLINK-32353
1 61587U 24187P   26330.27229941  .00012220  00000+0  37217-3 0  9998
2 61587  53.1618 283.5797 0001405 118.2255 241.8890 15.34403107119166
STARLINK-34400
1 64463U 25130AB  26330.25302067  .00011743  00000+0  35807-3 0  9994
2 64463  53.1579 313.3204 0002248 108.5662 251.5585 15.34405386 80755
STARLINK-34272
1 64274U 25120U   26330.31045835  .00019366  00000+0  58263-3 0  9997
2 64274  53.1630 337.0846 0001797  97.3893 262.7314 15.34409692 83938
STARLINK-36673
1 67660U 26022N   26330.09707365  .00010601  00000+0  33018-3 0  9993
2 67660  97.2843   0.7146 0002316 102.5077 257.6426 15.33449071 45639
STARLINK-35959
1 66827U 25280G   26330.20904441  .00010191  00000+0  34933-3 0  9990
2 66827  70.0016  30.9042 0001408 165.5152 194.6064 15.30426130 55354
STARLINK-30335
1 57710U 23129N   26330.25002315  .00103853  00000+0  32382-2 0  9998
2 57710  43.0017  80.5663 0000771 277.0103 201.7233 15.32163563  5817
STARLINK-31705
1 59382U 24062J   26329.67905105 -.00000118  00000+0  76573-5 0  9999
2 59382  53.1591 118.1770 0001639  96.2152 263.9037 15.34387010149660
STARLINK-31141
1 58803U 24012S   26329.51553830 -.00000430  00000+0 -61177-5 0  9990
2 58803  43.0016 132.4293 0000757 233.9220 126.1561 15.27584243161128
STARLINK-37186
1 68617U 26073R   26330.03901266  .00010007  00000+0  31184-3 0  9994
2 68617  97.2834 167.0531 0001938  95.6125 264.5339 15.33449897 35930
STARLINK-32683
1 62445U 24251P   26330.04836824  .00003248  00000+0  10725-3 0  9998
2 62445  53.1577 196.3515 0001363  96.8524 263.2633 15.34396763108032
STARLINK-4049
1 52840U 22062L   26330.25002315  .00180898  23781-4  47179-3 0  9993
2 52840  53.1539 236.1331 0000984 151.0793 229.1704 15.94636717  5978
STARLINK-32212
1 60256U 24131B   26330.20647045  .00089398  00000+0  14065-2 0  9996
2 60256  53.1518 268.0618 0007262 104.7698 255.4118 15.52855233131894
STARLINK-36947
1 68033U 26040U   26330.28747622  .00015051  00000+0  46758-3 0  9998
2 68033  97.2848 276.6906 0001487 118.3152 241.8240 15.33446301 41553
STARLINK-35382
1 66019U 25232Q   26330.25108041  .00010190  00000+0  31226-3 0  9998
2 66019  53.1592 323.8827 0002069 108.8160 251.3067 15.34405165 63550
STARLINK-37286
1 68512U 26068E   26330.07624777  .00006494  00000+0  20316-3 0  9995
2 68512  53.1614 342.3174 0001730  97.3063 262.8137 15.34402841 37143
STARLINK-11119 [DTC]
1 60121U 24118J   26330.15995610 -.00000370  00000+0  18154-5 0  9990
2 60121  53.1590  12.0514 0001958 102.2013 257.9224 15.69709932139061
STARLINK-35958
1 66822U 25280B   26330.20881369  .00009992  00000+0  35152-3 0  9999
2 66822  70.0007  30.9033 0001646 164.2671 195.8556 15.29586374 55359
STARLINK-11682 [DTC]
1 64234U 25119B   26329.66668981 -.00024385  00000+0 -17504-3 0  9992
2 64234  43.0010  76.2911 0001508 278.0998 194.7508 15.72403821  6005
STARLINK-37469
1 69168U 26111W   26329.69951217  .00014533  00000+0  44037-3 0  9999
2 69168  53.1564 116.1661 0001941  96.4465 263.6758 15.34403593 29068
STARLINK-31636
1 59747U 24090T   26329.78758819 -.00000314  00000+0 -18848-5 0  9995
2 59747  42.9997 148.7747 0000844 232.8628 127.2147 15.27579900143376
STARLINK-32209
1 60590U 24150H   26330.27983747  .00009805  00000+0  30097-3 0  9996
2 60590  53.1573 165.4708 0000777  95.7340 264.3751 15.34398221128182
STARLINK-37822
1 69424U 26126E   26330.10514508  .00009489  00000+0  29584-3 0  9990
2 69424  97.2838 190.4281 0001992  97.3490 262.7979 15.33449446 27736
STARLINK-1404
1 45686U 20035AF  26330.11212999  .00042970  00000+0  32967-3 0  9998
2 45686  53.1467 239.1223 0002994 134.0645 226.0620 15.71665785359507
STARLINK-33626
1 63369U 25063G   26330.22544369  .00018340  00000+0  55248-3 0  9994
2 63369  53.1604 269.7196 0000882 104.5484 255.5617 15.34407558 94862
STARLINK-32155
1 60274U 24131V   26330.25002315  .00035299  00000+0  10519-2 0  9997
2 60274  53.1611 275.5230 0001469  85.4770 241.9454 15.34371492  5826
STARLINK-32453
1 61671U 24193L   26330.23533906  .00008403  00000+0  28204-3 0  9998
2 61671  53.1597 314.5057 0001417 108.2292 251.8864 15.31724186116599
STARLINK-3822
1 52287U 22041AC  26330.09957502  .00027902  00000+0  90148-3 0  9998
2 52287  53.1614 351.3917 0002338  97.4301 262.6967 15.31914426254167
STARLINK-4257
1 53184U 22083BE  26330.05173450  .00112853  00000+0  34888-2 0  9997
2 53184  53.1610   8.9879 0001225 102.5729 257.5410 15.32648231241675
STARLINK-5312
1 55311U 23010AU  26330.25002315 -.00000224  00000+0 -21311-5 0  9995
2 55311  70.0002  57.5893 0003421 261.0544 193.7228 15.38809209  5849
STARLINK-2722
1 48472U 21040AW  26330.31077682  .00013034  00000+0  63564-3 0  9997
2 48472  53.0559  84.2200 0001163 155.3273 204.7778 15.18644145306101
STARLINK-35829
1 66350U 25254C   26329.42178994  .00014845  00000+0  48897-3 0  9994
2 66350  53.1557  99.3400 0002070  95.8564 264.2673 15.31712751 58926
STARLINK-34198
1 64388U 25127U   26329.53158257  .00007766  00000+0  29137-3 0  9994
2 64388  42.9951 146.3156 0000762 234.1948 125.8832 15.27586574 82361
STARLINK-4664
1 53777U 22111E   26330.03667682 -.00000032  00000+0  99801-5 0  9995
2 53777  53.1593 164.8946 0001273  95.5233 264.5915 15.35398686233368
STARLINK-32722
1 62450U 24251U   26329.80466617  .00009949  00000+0  30525-3 0  9999
2 62450  53.1572 197.6750 0001457  96.8470 263.2699 15.34394476107627
STARLINK-3528
1 51714U 22016A   26329.94714082  .00011669  00000+0  21181-3 0  9994
2 51714  53.1579 210.1050 0001139 129.3814 230.7296 15.50076301263223
STARLINK-4181
1 52882U 22062BE  26330.29192689  .00019286  00000+0  21448-3 0  9994
2 52882  53.1543 252.5258 0002829 104.8689 255.2639 15.62932317245938
STARLINK-1302
1 45375U 20019R   26330.06040383  .00307821  53108-4  48021-3 0  9994
2 45375  53.0296 293.8033 0005393 119.6469 240.5099 16.03535321371071
STARLINK-35302
1 65845U 25221Y   26330.24354512  .00012576  00000+0  38259-3 0  9990
2 65845  53.1606 307.4131 0002532 108.9293 251.1985 15.34410383 64861
STARLINK-37430
1 68863U 26094AA  26330.00356463  .00009372  00000+0  29223-3 0  9994
2 68863  97.2833 342.2375 0001211  97.3171 262.8210 15.33448089 32858
STARLINK-35120
1 65659U 25211H   26330.07892897  .00001014  00000+0  33775-4 0  9990
2 65659  97.2865   9.3147 0002755 102.2137 257.9414 15.33401483 66724
STARLINK-4114
1 53162U 22083AG  26330.25002315  .00059859  00000+0  13927-2 0  9993
2 53162  53.1601  46.7836 0000963 105.2176 199.0331 15.41760407  5869
STARLINK-5338
1 55286U 23010T   26330.25002315  .00081722  00000+0  29614-2 0  9997
2 55286  70.0021  83.8560 0004118 280.8564 191.3657 15.27933046  5765
STARLINK-4644
1 53719U 22107V   26329.66748672  .00000846  00000+0  36192-4 0  9997
2 53719  53.1584 112.4896 0002007  96.7993 263.3238 15.34382954234501
STARLINK-32450
1 61969U 24210L   26329.52488850  .00011450  00000+0  42488-3 0  9991
2 61969  42.9988 139.6461 0000933 232.6057 127.4710 15.27585410114751
STARLINK-31930
1 60596U 24150P   26330.24626714  .00008481  00000+0  26200-3 0  9997
2 60596  53.1561 165.9685 0001440  96.1977 263.9190 15.34384182128096
STARLINK-2575
1 48396U 21038AV  26330.03467458  .00036778  00000+0  34304-3 0  9992
2 48396  53.1581 190.8862 0001208  97.4295 262.6859 15.66993950308783
STARLINK-2499
1 48377U 21038AA  26330.08379560  .00005086  00000+0  16256-3 0  9996
2 48377  53.1574 210.9548 0002896 134.1836 225.9405 15.34205836307280
STARLINK-34741
1 65097U 25167V   26330.11862375  .00014018  00000+0  42527-3 0  9991
2 65097  53.1593 242.5573 0001257 104.5198 255.5944 15.34398226 73643
STARLINK-2564
1 48284U 21036J   26330.25697350  .00053779  00000+0  44287-3 0  9997
2 48284  53.1585 292.3064 0001643 117.8669 242.2516 15.69872860308569
STARLINK-35337
1 65831U 25221J   26330.23723619  .00014572  00000+0  44144-3 0  9990
2 65831  53.1589 307.3784 0002553 107.9545 252.1736 15.34408242 66218
STARLINK-37816
1 69478U 26131A   26330.06548589  .00015425  00000+0  47894-3 0  9997
2 69478  97.2863 350.0502 0002745  97.4745 262.6809 15.33454947 27108
STARLINK-36746
1 67733U 26026N   26330.14459788  .00011907  00000+0  37041-3 0  9997
2 67733  97.2833  21.0159 0003197 102.8565 257.3036 15.33454444 44196
STARLINK-5989
1 56781U 23078Q   26330.25002315  .00021272  00000+0  90672-3 0  9999
2 56781  70.0011  43.9251 0001851 252.2151 192.3282 15.22925372  5713
STARLINK-11690 [DTC]
1 64242U 25119K   26330.30303928  .00058685  00000+0  42731-3 0  9999
2 64242  43.0014  71.8427 0001076 153.8992 206.1931 15.72463814 86391
STARLINK-35024
1 65410U 25192G   26330.31659034  .00008964  00000+0  30012-3 0  9995
2 65410  53.1586  94.7695 0001685  95.8508 264.2686 15.31717855 69474
ONEWEB-0039
1 45146U 20008R   26330.08638362  .00000667  00000+0  18730-2 0  9999
2 45146  87.8869   4.0787 0001390  93.7207 266.4079 13.10374188329368
ONEWEB-0721
1 56725U 23068R   26330.12559591  .00000013  00000+0 -30986-5 0  9990
2 56725  86.6804  33.8085 0001878 103.4863 256.6480 13.22479162179347
ONEWEB-0618
1 55159U 23004V   26329.88257555  .00002840  00000+0  15269-3 0  9990
2 55159  86.9036 111.8873 0013722  57.3660 302.8902 15.14371577206722
ONEWEB-0266
1 48984U 21060T   26330.00319833  .00000141  00000+0  33420-3 0  9993
2 48984  87.9167 196.6989 0001557  79.4621 280.6686 13.16594553260879
ONEWEB-0611
1 54674U 22166AJ  26330.09630931  .00000050  00000+0  90102-4 0  9998
2 54674  87.9331 211.8033 0001543  79.7904 280.3404 13.21826833192837
ONEWEB-0640
1 55804U 23029J   26329.84698356 -.00000140  00000+0 -41262-3 0  9993
2 55804  87.8959 257.4712 0001105  73.1608 286.9643 13.14503083180656
ONEWEB-0516
1 54134U 22138X   26330.24388784  .00000042  00000+0  73235-4 0  9990
2 54134  87.9142 272.6494 0001937  81.7503 278.3850 13.19726536198204
ONEWEB-0573
1 55152U 23004N   26329.95953146  .00000017  00000+0  10612-4 0  9992
2 55152  87.9105 303.2711 0002657  87.2328 272.9108 13.18697434191014
ONEWEB-0293
1 49082U 21075H   26330.00857446 -.00000014  00000+0 -69627-4 0  9996
2 49082  87.9110 333.7222 0001968  89.8019 270.3338 13.17650617254949
ONEWEB-0439
1 51635U 22012P   26330.06090377 -.00000417  00000+0 -12314-2 0  9999
2 51635  87.8873   4.0832 0001708  93.1899 266.9423 13.10376479230520
ONEWEB-0284
1 49002U 21060AM  26330.02853266  .00000005  00000+0 -20094-4 0  9994
2 49002  87.9169 196.6921 0001762  80.6125 279.5205 13.16595461260895
ONEWEB-0550
1 56052U 23043G   26330.06772267 -.00000013  00000+0 -62413-4 0  9996
2 56052  87.9331 211.8075 0001274  79.9093 280.2184 13.21825902179618
ONEWEB-0383
1 49309U 21090AG  26329.85713654  .00000005  00000+0 -22214-4 0  9998
2 49309  87.8977 257.4486 0001600  72.4974 287.6330 13.14502623248830
ONEWEB-0644
1 55808U 23029N   26330.19114234  .00000084  00000+0  17748-3 0  9992
2 55808  87.9143 272.6633 0002025  81.8422 278.2941 13.19727813180276
ONEWEB-0432
1 50502U 21132AK  26330.23035711  .00000021  00000+0  20743-4 0  9999
2 50502  87.9112 303.2030 0002246  87.0963 273.0426 13.18832077242934
ONEWEB-0027
1 45432U 20020J   26330.30880837  .00000023  00000+0  27064-4 0  9990
2 45432  87.9114 333.6665 0002289  90.0978 270.0416 13.17649995327709
ONEWEB-0706
1 61611U 24188T   26330.08977632  .00000717  00000+0  20173-2 0  9993
2 61611  87.8868   4.0773 0002435  94.5418 265.5988 13.10373323104585
ONEWEB-0256
1 48974U 21060H   26330.01982756  .00000061  00000+0  12728-3 0  9992
2 48974  87.9157 196.6971 0001347  79.0754 281.0528 13.16594647260928
ONEWEB-0592
1 54658U 22166S   26330.07612567  .00000112  00000+0  24027-3 0  9994
2 54658  87.9323 211.8030 0001620  79.6158 280.5159 13.21826847192826
ONEWEB-0349
1 49281U 21090C   26330.19599192  .00000075  00000+0  15371-3 0  9996
2 49281  87.9236 242.1360 0001676  73.3395 286.7922 13.20772037251218
ONEWEB-0518
1 54136U 22138Z   26330.24225605  .00000011  00000+0 -41438-5 0  9992
2 54136  87.9145 272.6497 0001980  81.5320 278.6037 13.19725725198204
ONEWEB-0119
1 47265U 20100H   26330.20909715  .00000182  00000+0  47167-3 0  9992
2 47265  87.8902 318.4628 0001819  87.3540 272.7797 13.12440502285950
ONEWEB-0081
1 45429U 20020F   26330.05185791  .00000591  00000+0  16320-2 0  9996
2 45429  87.8899 348.9123 0001653  89.5704 270.5613 13.11416066323958
ONEWEB-0415
1 51624U 22012C   26330.11692955  .00000422  00000+0  11728-2 0  9995
2 51624  87.8879   4.0748 0002600  93.1976 266.9449 13.10369276230499
ONEWEB-0272
1 48990U 21060Z   26330.06652208  .00000008  00000+0 -11951-4 0  9991
2 48990  87.9164 196.6893 0001205  81.0142 279.1125 13.16594453260919
ONEWEB-0244
1 48776U 21045K   26330.15258594  .00000097  00000+0  22364-3 0  9992
2 48776  87.9082 226.9420 0001567  80.4428 279.6880 13.15547251266264
ONEWEB-0220
1 48232U 21031Y   26330.22670496 -.00000315  00000+0 -88341-3 0  9993
2 48232  87.8973 257.3743 0001904  72.4901 287.6436 13.14502650269696
ONEWEB-0168
1 48068U 21025AC  26329.94306413  .00000153  00000+0  38279-3 0  9998
2 48068  87.8911 287.9944 0001932  81.8930 278.2418 13.13468982274795
ONEWEB-0132
1 47278U 20100W   26330.29549827 -.00000195  00000+0 -57746-3 0  9999
2 47278  87.8896 318.4510 0001733  87.0572 273.0755 13.12442190285844
ONEWEB-0309
1 49095U 21075W   26330.28869658  .00000037  00000+0  63320-4 0  9995
2 49095  87.9105 333.6635 0002279  90.1989 269.9404 13.17649213254927
ONEWEB-0458
1 51648U 22012AC  26330.05410885 -.00000583  00000+0 -17055-2 0  9993
2 51648  87.8869   4.0707 0001631  94.5830 265.5484 13.10373643230505
ONEWEB-0281
1 48999U 21060AJ  26330.01586565  .00000090  00000+0  20147-3 0  9993
2 48999  87.9167 196.6951 0001368  77.9510 282.1774 13.16593608260925
ICEYE-X2
1 43800U 18099AU  26330.28685959  .00005576  00000+0  26035-3 0  9999
2 43800  97.4147  21.3023 0011226  95.6671 264.5844 15.20170887437428
ICEYE-X61
1 66754U 25276CS  26330.15296468  .00001530  00000+0  75323-4 0  9997
2 66754  97.4052  40.5728 0004449 117.7636 242.4050 15.19476097 55001
ICEYE-X56
1 64574U 25135AY  26330.29628693  .00001251  00000+0  11548-3 0  9991
2 64574  97.7886  84.2157 0002743 127.6490 232.4980 14.96436670 78161
ICEYE-X37
1 59102U 24043E   26330.16570111  .00001465  00000+0  10152-3 0  9993
2 59102  97.8514 110.0710 0015033 175.9356 184.1996 15.07200929149479
ICEYE-X25
1 56963U 23084AH  26330.25320502  .00032859  00000+0  25784-3 0  9994
2 56963  97.5987 129.2520 0003372  60.6799 299.4800 15.71486001193333
ICEYE-X51
1 63257U 25052AZ  26329.99762456  .00002719  00000+0  21853-3 0  9997
2 63257  97.7893 225.0688 0002070  60.4558 299.6872 15.00868840 93078
ICEYE-X72
1 68504U 26067CS  26329.84442105  .00001830  00000+0  15588-3 0  9995
2 68504  97.7866 287.3696 0004053 116.9224 243.2414 14.99030345 35923
ICEYE-X63
1 67372U 26004K   26330.23216167  .00000406  00000+0  49493-4 0  9991
2 67372  97.8090 326.4496 0003376 334.3990  25.7059 14.88542579 47400
ICEYE-X66
1 66301U 25248J   26330.12034453  .00001224  00000+0  89942-4 0  9997
2 66301  45.4018 359.9376 0004778 217.0471 143.0082 15.09827843 59975
ICEYE-X4
1 44390U 19038D   26330.29771031  .00006178  00000+0  19879-3 0  9993
2 44390  97.8573  12.9775 0013798  94.1535 266.1283 15.32550303406608
ICEYE-X42
1 62698U 25009CT  26330.18724386  .00000795  00000+0  84014-4 0  9991
2 62698  97.7415  45.5816 0001241 101.4760 258.6599 14.91930745101449
ICEYE-X52
1 64572U 25135AW  26330.30452484  .00000701  00000+0  70975-4 0  9993
2 64572  97.7885  82.9654 0002711 125.7246 234.4227 14.94059264 78083
ICEYE-X36
1 59103U 24043F   26330.26964839  .00002534  00000+0  16850-3 0  9998
2 59103  97.8575 111.6017 0006829 125.0295 235.1574 15.08115008149568
ICEYE-X46
1 63258U 25052BA  26330.13915921  .00000594  00000+0  58286-4 0  9993
2 63258  97.6699 218.7640 0001545  79.4094 280.7302 14.95779427 92859
ICEYE-X71
1 68494U 26067CG  26328.59655764 -.00000174  00000+0 -89435-5 0  9997
2 68494  97.7638 285.6571 0003175 143.4758 216.6683 15.00834229 35728
ICEYE-X7
1 46496U 20068L   26330.27811765  .00005035  00000+0  15332-3 0  9995
2 46496  97.7695 304.7264 0008823 107.2866 252.9342 15.34461559339624
ICEYE-X67
1 66302U 25248K   26330.14798842  .00001516  00000+0  10783-3 0  9999
2 66302  45.4061 359.4529 0006236 214.9972 145.0502 15.09787732 59995
ICEYE-X65
1 66300U 25248H   26330.10410127  .00001430  00000+0  10598-3 0  9999
2 66300  45.4052   2.3792 0005229 218.5354 141.5155 15.08518349 59961
ICEYE-X58
1 66751U 25276CP  26330.13056138  .00001260  00000+0  65447-4 0  9996
2 66751  97.4051  40.1729 0004553 118.3843 241.7850 15.17888247 54974
ICEYE-X55
1 64581U 25135BF  26330.27242463  .00002179  00000+0  19913-3 0  9998
2 64581  97.7882  83.9316 0003791 145.6005 214.5462 14.96067456 78140
ICEYE-X38
1 59100U 24043C   26330.29607653  .00002988  00000+0  16789-3 0  9990
2 59100  97.8595 118.8916 0001472 229.9010 130.2091 15.14131168136198
ICEYE-X77
1 69892U 26156Z   26329.65285902  .00000919  00000+0  92288-4 0  9994
2 69892  97.7428 227.8695 0008651  53.1418 307.0594 14.93641205 21088
ICEYE-X75
1 68497U 26067CK  26329.83500286  .00000714  00000+0  75181-4 0  9996
2 68497  97.7820 285.9047 0002790 107.4645 252.6880 14.92415595 35834
ICEYE-X64
1 67373U 26004L   26330.24637011  .00000402  00000+0  49561-4 0  9990
2 67373  97.8103 326.3295 0005239 336.6408  23.4570 14.88175815 47397
IRIDIUM 152
1 43479U 18047D   26330.10433148  .00000013  00000+0 -24921-5 0  9992
2 43479  86.3975  20.6129 0002059  95.4588 264.6842 14.34217646445690
IRIDIUM 146
1 43254U 18030F   26330.03646326  .00000160  00000+0  50035-4 0  9997
2 43254  86.3993 222.7360 0002096  84.1243 276.0192 14.34220837453338
IRIDIUM 141
1 43077U 17083H   26329.92573096  .00000006  00000+0 -49085-5 0  9990
2 43077  86.3963 254.3418 0001973  84.2886 275.8535 14.34217443467354
IRIDIUM 173
1 43925U 19002D   26330.18920142  .00000053  00000+0  11904-4 0  9992
2 43925  86.3941 285.8355 0002215  86.3905 273.7544 14.34217541412172
IRIDIUM 178
1 56729U 23068V   26330.23739374  .00000266  00000+0  31629-4 0  9998
2 56729  86.6516 317.6838 0002379  89.7903 270.3591 14.80051278190335
IRIDIUM 165
1 43572U 18061D   26330.29118659  .00000133  00000+0  40576-4 0  9994
2 43572  86.3970 349.0495 0002267  92.5761 267.5694 14.34218079436626
IRIDIUM 162
1 43482U 18047G   26329.07956649  .00000172  00000+0  45040-4 0  9995
2 43482  86.4504  21.0680 0002172  95.3304 264.8145 14.43282845448336
IRIDIUM 145
1 43253U 18030E   26330.05550186  .00000152  00000+0  47294-4 0  9990
2 43253  86.3993 222.7164 0002270  83.4516 276.6938 14.34217216453328
IRIDIUM 113
1 42803U 17039A   26329.89401819  .00000032  00000+0  45053-5 0  9995
2 42803  86.3971 254.5552 0002028  84.1304 276.0123 14.34217651494364
IRIDIUM 172
1 43927U 19002F   26329.97355242  .00000069  00000+0  17554-4 0  9996
2 43927  86.3942 285.9341 0002159  86.9037 273.2406 14.34217768412151
IRIDIUM 139
1 42963U 17061J   26329.70424120  .00000104  00000+0  30018-4 0  9991
2 42963  86.3949 317.8253 0002234  90.1741 269.9711 14.34218987477995
IRIDIUM 160
1 43569U 18061A   26330.06285266  .00000105  00000+0  30519-4 0  9999
2 43569  86.3970 349.1372 0002162  91.6110 268.5334 14.34218221436574
IRIDIUM 112
1 41925U 17003J   26330.13604412  .00000144  00000+0  44474-4 0  9992
2 41925  86.3979  20.5767 0002103  95.6892 264.4543 14.34217951516486
IRIDIUM 124
1 42810U 17039H   26330.07856050 -.00000030  00000+0 -14788-4 0  9996
2 42810  86.4515 222.6566 0002017  84.8559 275.2873 14.43279549498467
IRIDIUM 131
1 43079U 17083K   26330.26188839  .00000166  00000+0  52227-4 0  9995
2 43079  86.3969 254.3979 0002109  84.4314 275.7122 14.34217818467339
IRIDIUM 121
1 42812U 17039K   26329.96086787  .00000040  00000+0  73743-5 0  9992
2 42812  86.3944 286.0441 0002236  85.5536 274.5915 14.34217623493016
IRIDIUM 128
1 42811U 17039J   26330.21165167  .00000016  00000+0 -14338-5 0  9990
2 42811  86.3945 317.4377 0002289  89.7775 270.3684 14.34217673493205
IRIDIUM 105
1 41921U 17003E   26328.76269525  .00000173  00000+0  54776-4 0  9994
2 41921  86.3973 349.7577 0001998  92.5940 267.5485 14.34219344518155
IRIDIUM 109
1 41919U 17003C   26330.09164647  .00000009  00000+0 -37674-5 0  9997
2 41919  86.3982  20.8124 0002058  94.6659 265.4772 14.34217553516407
IRIDIUM 140
1 43252U 18030D   26330.06818228  .00000153  00000+0  47717-4 0  9995
2 43252  86.3988 222.7386 0001964  83.4031 276.7388 14.34217873453394
IRIDIUM 151
1 43074U 17083E   26329.88133368 -.00000009  00000+0 -10366-4 0  9999
2 43074  86.3963 254.4323 0002024  83.0974 277.0452 14.34217330467313
IRIDIUM 117
1 42808U 17039F   26329.91646986  .00000107  00000+0  31045-4 0  9997
2 42808  86.3940 285.9797 0002259  87.8354 272.3101 14.34217690493159
IRIDIUM 179
1 56730U 23068W   26330.23203013  .00000232  00000+0  27180-4 0  9996
2 56730  86.6762 327.1521 0002484  90.6251 269.5256 14.80040762190333
IRIDIUM 154
1 43574U 18061F   26330.30387073  .00000124  00000+0  37367-4 0  9990
2 43574  86.3970 349.0469 0002214  91.5737 268.5713 14.34218021436645
FLOCK 4H-21
1 66724U 25276BL  26330.18307445  .00003182  00000+0  14000-3 0  9994
2 66724  97.4036  41.5021 0004039  96.7778 263.3918 15.22580993 54329
FLOCK 4Q-4
1 58327U 23174BZ  26330.21512171  .00017727  00000+0  25253-3 0  9999
2 58327  97.3763  62.2991 0003002 288.2392  71.8536 15.56387222170694
FLOCK 4G-30
1 62679U 25009BY  26330.21522104  .00040732  00000+0  81742-3 0  9997
2 62679  97.3796  52.5841 0001994  96.4770 263.6707 15.46573870161458
FLOCK 4Q-28
1 58280U 23174AA  26330.25757025  .00327275  41062-4  11515-2 0  9995
2 58280  97.3750  68.8771 0006519 278.8891  81.1643 15.89108810171077
FLOCK 4H-34
1 66737U 25276BZ  26330.17902864  .00003206  00000+0  14204-3 0  9996
2 66737  97.4094  41.6213 0003957  98.4710 261.6975 15.22341574 54317
FLOCK 4Q-7
1 58318U 23174BQ  26330.22824552  .00462254  85738-4  13336-2 0  9992
2 58318  97.3760  69.4988 0006709 288.7239  71.3307 15.93002567171091
FLOCK 4G-8
1 62613U 25009E   26330.21964840  .00006033  00000+0  19319-3 0  9999
2 62613  97.3913  51.7773 0005320  95.7382 264.4467 15.32751213103711
FLOCK 4Q-27
1 58282U 23174AC  26330.23200981  .00908347  53314-3  68340-3 0  9995
2 58282  97.3763  69.2714 0008217 274.7302  85.3046 16.15373897171155
FLOCK 4G-14
1 62667U 25009BL  26330.21131124  .00005829  00000+0  18978-3 0  9991
2 62667  97.3932  51.4965 0005531  98.8562 261.3306 15.32229471103689
FLOCK 4Q-11
1 58273U 23174T   26330.23942841  .00100760  00000+0  78306-3 0  9999
2 58273  97.3748  64.5651 0005415 294.0021  66.0675 15.71449532170802
FLOCK 4G-34
1 62650U 25009AT  26330.19326297  .00006009  00000+0  19639-3 0  9993
2 62650  97.3871  51.1026 0003504  95.0662 265.0980 15.32099069103694
FLOCK 4Q-13
1 58328U 23174CA  26330.24551631  .00018412  00000+0  22989-3 0  9992
2 58328  97.3984  66.6799 0004106 266.9050  93.1737 15.59906585170865
FLOCK 4G-15
1 62678U 25009BX  26330.20115923  .00006276  00000+0  20187-3 0  9993
2 62678  97.3922  51.5854 0005992  99.2708 260.9212 15.32588604161339
FLOCK 4Q-16
1 58271U 23174R   26330.18346677  .00020266  00000+0  28323-3 0  9992
2 58271  97.3749  62.7760 0004555 299.7536  60.3265 15.56871611170711
FLOCK 4G-12
1 62625U 25009S   26330.17745720  .00006329  00000+0  20555-3 0  9993
2 62625  97.3929  51.4842 0005366  95.0003 265.1852 15.32274373103687
FLOCK 4Q-2
1 58320U 23174BS  26330.28909473  .00017456  00000+0  24015-3 0  9998
2 58320  97.3803  63.4282 0003778 254.6355 105.4483 15.57331365170765
LEMUR-2-SQUIRRELCOMM
1 60578U 24149DQ  26328.24382485  .00003770  00000+0  24423-3 0  9997
2 60578  97.6620  48.7269 0004479 213.4283 146.6663 15.08741368124520
LEMUR-2-KRISH
1 64561U 25135AK  26330.11533046  .00002155  00000+0  18993-3 0  9990
2 64561  97.7875  84.1582 0000730 107.3290 252.8012 14.97501909 78142
LEMUR-1
1 40044U 14033AL  26330.22458444  .00001051  00000+0  12515-3 0  9993
2 40044  97.8084 116.7269 0046472 266.2850  93.3051 14.85751118669524
LEMUR-2-FURIAUS
1 42840U 17042R   26330.05051397  .00011989  00000+0  34865-3 0  9992
2 42840  97.3768 148.1970 0005545 197.9390 162.1659 15.35583586513005
LEMUR-2-MONSON
1 42839U 17042Q   26330.28146224  .00008348  00000+0  27006-3 0  9993
2 42839  97.4092 150.4820 0006799 127.7160 232.4699 15.32292312512951
LEMUR-2-VALHALLA
1 58900U 24022B   26330.05757590  .00009983  00000+0  21848-3 0  9998
2 58900  97.3872 182.3511 0006366 245.7096 114.3487 15.44319855157214
LEMUR-2-DELOITTE-6
1 69882U 26156P   26329.64631520  .00000770  00000+0  82235-4 0  9999
2 69882  97.7468 227.6351 0004871  56.8844 303.2842 14.91631793 21062
LEMUR-2-CLARA
1 68483U 26067BV  26330.11286603  .00001753  00000+0  17123-3 0  9993
2 68483  97.7876 286.6180 0004708 101.7102 258.4647 14.93666577 35892
LEMUR-2-HCS-BAZUS
1 67388U 26004AB  26330.26770470  .00002502  00000+0  25759-3 0  9990
2 67388  97.8094 327.4190 0000858 274.7205  85.3916 14.91182546 47458
LEMUR-2-DEVERILL-M-T
1 57391U 23100F   26330.27690028  .00022199  00000+0  41699-3 0  9993
2 57391  99.4307  56.8864 0005047 205.6668 154.4326 15.48641775186250
LEMUR-2-SEJONG-2
1 64586U 25135BL  26330.29878271  .00001309  00000+0  11627-3 0  9995
2 64586  97.7836  84.4861 0004922 133.0934 227.0701 14.97878214 78209
LEMUR-2-EMBRIONOVIS
1 56970U 23084AQ  26329.98195275  .00009207  00000+0  22027-3 0  9995
2 56970  97.6462 121.1125 0008948  95.3893 264.8374 15.41668508192574
LEMUR-2-ANDIS
1 42838U 17042P   26330.24872758  .00014246  00000+0  33951-3 0  9996
2 42838  97.3648 151.6880 0007526 132.2483 227.9403 15.41662728513363
LEMUR-2-CAEL
1 58903U 24022E   26329.97618207  .00011010  00000+0  22748-3 0  9995
2 58903  97.3848 183.5266 0005031 220.7798 139.3076 15.45995006157298
LEMUR-2-DELOITTE-4
1 69934U 26156BT  26329.44743536  .00000890  00000+0  91025-4 0  9996
2 69934  97.7445 227.7005 0010701  56.1208 304.1028 14.93001394 21053
LEMUR-2-POLO-11
1 68493U 26067CF  26330.12553391  .00001012  00000+0  10279-3 0  9999
2 68493  97.7885 286.3218 0002738 177.5523 182.5711 14.93019721 35871
NOAA 15
1 25338U 98030A   26330.27753586  .00000064  00000+0  43803-4 0  9999
2 25338  98.4987 346.4749 0009405 227.9818 132.0563 14.27186994484412
NOAA 18
1 28654U 05018A   26330.26479460  .00000043  00000+0  45989-4 0  9996
2 28654  98.8000  47.4888 0014996  53.2453 307.0094 14.13756507109217
NOAA 19
1 33591U 09005A   26330.29275646  .00000025  00000+0  37083-4 0  9991
2 33591  98.9362  41.1425 0012791 293.0497  66.9326 14.13495687917389
""".trimIndent()
    }
}
