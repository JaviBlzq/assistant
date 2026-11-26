package com.javiblzq.assistant.providers

import java.text.Normalizer
import java.util.Locale
import kotlin.math.round

data class CatalogExercise(
    val id: String,
    val displayName: String,
    val aliases: List<String>,
    val primaryMuscles: List<String>,
    val secondaryMuscles: List<String>,
    val loadType: String,
    val e1rmCapable: Boolean
)

data class WorkoutTemplate(
    val id: String,
    val name: String,
    val exerciseIds: List<String>
)

data class TemplateMatch(
    val templateId: String?,
    val templateName: String?,
    val confidence: String // exact | strong | ambiguous | none
)

object SemyraCatalog {
    private val e1rmCapableIds = setOf(
        "high_bar_squat", "incline_dumbbell_press", "plate_loaded_chest_press",
        "barbell_romanian_deadlift", "t_bar_row", "barbell_bench_press",
        "bulgarian_split_squat", "barbell_deadlift", "barbell_row",
        "pronated_lat_pulldown", "gironda_upper_back_row"
    )

    private fun entry(
        id: String,
        displayName: String,
        loadType: String,
        primaryMuscles: List<String>,
        secondaryMuscles: List<String> = emptyList(),
        aliases: List<String> = emptyList()
    ) = CatalogExercise(
        id = id,
        displayName = displayName,
        aliases = aliases,
        primaryMuscles = primaryMuscles,
        secondaryMuscles = secondaryMuscles,
        loadType = loadType,
        e1rmCapable = id in e1rmCapableIds
    )

    val EXERCISES: List<CatalogExercise> = listOf(
        entry("hanging_leg_raise", "Elevación de piernas colgado", "bodyweight", listOf("abdominals")),
        entry("high_bar_squat", "Sentadilla con barra (High Bar)", "external", listOf("quadriceps"), listOf("glutes"), listOf("Sentadilla con barra High Bar")),
        entry("incline_dumbbell_press", "Press inclinado mancuernas", "external", listOf("chest"), listOf("anterior_deltoid", "triceps")),
        entry("dumbbell_lateral_raise", "Elevaciones laterales con mancuernas", "external", listOf("lateral_deltoid")),
        entry("plate_loaded_chest_press", "Press tumbado máquina discos", "external", listOf("chest"), listOf("triceps")),
        entry("high_cable_bar_triceps_extension", "Extensión de tríceps desde polea alta con barra", "external", listOf("triceps")),
        entry("rear_cable_lateral_raise", "Elevaciones laterales por detrás en polea a la altura del sacro", "external", listOf("lateral_deltoid")),
        entry("dead_bug", "Dead Bug", "bodyweight", listOf("abdominals")),
        entry("pull_up", "Dominadas", "bodyweight", listOf("lats"), listOf("upper_back", "biceps")),
        entry("barbell_romanian_deadlift", "Peso muerto rumano con barra", "external", listOf("hamstrings", "glutes"), listOf("spinal_erectors")),
        entry("t_bar_row", "Remo en T", "external", listOf("upper_back", "lats"), listOf("biceps")),
        entry("seated_leg_curl", "Curl femoral sentado", "external", listOf("hamstrings")),
        entry("ez_bar_biceps_curl", "Curl bíceps barra Z", "external", listOf("biceps")),
        entry("scott_machine_biceps_curl", "Curl de bíceps en máquina banco Scott", "external", listOf("biceps")),
        entry("ab_wheel", "Rueda abdominal", "bodyweight", listOf("abdominals")),
        entry("barbell_bench_press", "Press banca", "external", listOf("chest"), listOf("anterior_deltoid", "triceps")),
        entry("bulgarian_split_squat", "Sentadilla búlgara", "external", listOf("quadriceps", "glutes")),
        entry("pec_deck", "Peck Deck", "external", listOf("chest")),
        entry("incline_dumbbell_french_press", "Press francés con mancuernas en banco a 30grados", "external", listOf("triceps"), emptyList(), listOf("Press francés con mancuernas en banco a 30 grados")),
        entry("front_cable_lateral_raise", "Elevaciones laterales por delante en polea a la altura del sacro", "external", listOf("lateral_deltoid")),
        entry("high_cable_cuff_triceps_extension", "Extensión de tríceps con muñequera desde polea alta", "external", listOf("triceps")),
        entry("barbell_deadlift", "Peso muerto convencional", "external", listOf("glutes", "hamstrings", "spinal_erectors")),
        entry("barbell_row", "Remo con barra", "external", listOf("upper_back", "lats"), listOf("biceps")),
        entry("lying_leg_curl", "Curl femoral tumbado", "external", listOf("hamstrings")),
        entry("pronated_lat_pulldown", "Jalón al pecho agarre prono", "external", listOf("lats"), listOf("upper_back", "biceps")),
        entry("gironda_upper_back_row", "Remo Gironda con énfasis en espalda alta", "external", listOf("upper_back"), listOf("lats", "biceps")),
        entry("supported_extended_arm_dumbbell_curl", "Curl de bíceps mancuernas brazo en extensión apoyado", "external", listOf("biceps"))
    )

    val WORKOUT_TEMPLATES: List<WorkoutTemplate> = listOf(
        WorkoutTemplate("push_1", "Push 1", listOf("hanging_leg_raise", "high_bar_squat", "incline_dumbbell_press", "dumbbell_lateral_raise", "plate_loaded_chest_press", "high_cable_bar_triceps_extension", "rear_cable_lateral_raise")),
        WorkoutTemplate("pull_1", "Pull 1", listOf("dead_bug", "pull_up", "barbell_romanian_deadlift", "t_bar_row", "seated_leg_curl", "ez_bar_biceps_curl", "scott_machine_biceps_curl")),
        WorkoutTemplate("push_2", "Push 2", listOf("ab_wheel", "barbell_bench_press", "bulgarian_split_squat", "pec_deck", "incline_dumbbell_french_press", "front_cable_lateral_raise", "high_cable_cuff_triceps_extension")),
        WorkoutTemplate("pull_2", "Pull 2", listOf("dead_bug", "barbell_deadlift", "barbell_row", "lying_leg_curl", "pronated_lat_pulldown", "gironda_upper_back_row", "supported_extended_arm_dumbbell_curl"))
    )

    private val byId = EXERCISES.associateBy { it.id }
    private val byName = EXERCISES.associateBy { normalizeExerciseName(it.displayName) }
    private val byAlias = buildMap {
        for (item in EXERCISES) {
            for (alias in item.aliases) {
                put(normalizeExerciseName(alias), item)
            }
        }
    }

    fun normalizeExerciseName(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
        val noMarks = normalized.replace(Regex("[\\u0300-\\u036f]"), "")
        return noMarks.replace(Regex("\\s+"), " ").trim().lowercase(Locale("es"))
    }

    fun catalogExercise(id: String): CatalogExercise? = byId[id]

    fun resolveCatalogExercise(sourceName: String): CatalogExercise? {
        val norm = normalizeExerciseName(sourceName)
        return byAlias[norm] ?: byName[norm]
    }

    fun suggestTemplate(canonicalIds: List<String?>): TemplateMatch {
        val actual = canonicalIds.filterNotNull().toSet()
        if (actual.size < 5 || canonicalIds.any { it == null }) {
            return TemplateMatch(null, null, "none")
        }
        val candidates = mutableListOf<TemplateMatch>()
        for (template in WORKOUT_TEMPLATES) {
            val expected = template.exerciseIds.toSet()
            val missing = expected.count { it !in actual }
            val extra = actual.count { it !in expected }
            if (missing == 0 && extra == 0) {
                candidates.add(TemplateMatch(template.id, template.name, "exact"))
            } else if (missing <= 1 && extra <= 1 && actual.size >= expected.size - 1) {
                candidates.add(TemplateMatch(template.id, template.name, "strong"))
            }
        }
        return if (candidates.size == 1) {
            candidates.first()
        } else {
            TemplateMatch(null, null, if (candidates.size > 1) "ambiguous" else "none")
        }
    }

    fun estimatedOneRepMax(weightKg: Double?, reps: Int?): Double? {
        if (weightKg == null || reps == null || !weightKg.isFinite() || weightKg <= 0.0 || reps < 1 || reps > 12) {
            return null
        }
        return round(weightKg * (1.0 + reps / 30.0) * 10.0) / 10.0
    }
}
