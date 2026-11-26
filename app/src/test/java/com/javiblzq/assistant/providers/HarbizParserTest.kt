package com.javiblzq.assistant.providers

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarbizParserTest {

    @Test
    fun parsesCompletedWorkoutWithSupersetsAndE1rm() {
        val raw = JSONObject(
            """
            {
              "_id": "evt_push_1",
              "type": "workout",
              "title": "Push 1",
              "date": { "${'$'}date": 1795420800000 },
              "updatedAt": { "${'$'}date": 1795424400000 },
              "completed": true,
              "removed": false,
              "workout": {
                "exercises": [
                  {
                    "type": "simple",
                    "exerciseId": "ex1",
                    "name_provisional": "Elevación de piernas colgado",
                    "status": "completed",
                    "result": { "sets": [ { "reps": 12, "weight": 0, "completed": true } ] }
                  },
                  {
                    "type": "simple",
                    "exerciseId": "ex2",
                    "name_provisional": "Sentadilla con barra (High Bar)",
                    "status": "completed",
                    "result": {
                      "sets": [
                        { "reps": 5, "weight": 60, "setType": "warmup", "completed": true },
                        { "reps": 8, "weight": 100, "setType": "working", "completed": true }
                      ]
                    }
                  },
                  {
                    "type": "simple",
                    "exerciseId": "ex3",
                    "name_provisional": "Press inclinado mancuernas",
                    "status": "completed",
                    "result": { "sets": [ { "reps": 10, "weight": 30, "completed": true } ] },
                    "supersetExercises": [
                      {
                        "exerciseId": "ex4",
                        "name_provisional": "Elevaciones laterales con mancuernas",
                        "status": "completed",
                        "result": { "sets": [ { "reps": 15, "weight": 10, "completed": true } ] }
                      }
                    ]
                  },
                  {
                    "type": "simple",
                    "exerciseId": "ex5",
                    "name_provisional": "Press tumbado máquina discos",
                    "status": "completed",
                    "result": { "sets": [ { "reps": 10, "weight": 70, "completed": true } ] }
                  },
                  {
                    "type": "simple",
                    "exerciseId": "ex6",
                    "name_provisional": "Extensión de tríceps desde polea alta con barra",
                    "status": "completed",
                    "result": { "sets": [ { "reps": 12, "weight": 25, "completed": true } ] }
                  },
                  {
                    "type": "simple",
                    "exerciseId": "ex7",
                    "name_provisional": "Elevaciones laterales por detrás en polea a la altura del sacro",
                    "status": "completed",
                    "result": { "sets": [ { "reps": 14, "weight": 7.5, "completed": true } ] }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val workout = HarbizParser.parseWorkoutDetail(raw, "Europe/Madrid")
        assertNotNull(workout)
        assertEquals("Push 1", workout!!.title)
        assertEquals("Push 1", workout.routineName)
        assertEquals(7, workout.exercises.size)
        assertEquals(7, workout.workingSetsCount)

        val squat = workout.exercises[1]
        assertEquals("high_bar_squat", squat.catalogId)
        assertEquals("100 KG x 8", squat.bestSetText)
        // Epley: 100 * (1 + 8/30) = 126.7
        assertEquals(126.7, squat.topE1rmKg!!, 0.05)

        val supersetChild = workout.exercises[3]
        assertNotNull(supersetChild.supersetGroup)
        assertEquals("dumbbell_lateral_raise", supersetChild.catalogId)
        assertTrue(workout.volumeLoadKg > 1500.0)
    }

    @Test
    fun discoversScheduledAndCompletedWorkoutsIgnoringRemoved() {
        val arr = JSONArray(
            """
            [
              { "_id": "a1", "type": "workout", "title": "Pull 1", "date": { "${'$'}date": 1795507200000 }, "completed": false, "removed": false },
              { "_id": "a2", "type": "note", "title": "Ignored", "date": { "${'$'}date": 1795507200000 } },
              { "_id": "a3", "type": "workout", "title": "Removed", "date": { "${'$'}date": 1795507200000 }, "completed": true, "removed": true }
            ]
            """.trimIndent()
        )
        val events = HarbizParser.discoverWorkoutEvents(arr)
        assertEquals(1, events.size)
        assertEquals("a1", events[0].id)
        assertEquals(false, events[0].completed)
    }
}
