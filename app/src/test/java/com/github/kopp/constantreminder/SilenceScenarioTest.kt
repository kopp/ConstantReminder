package com.github.kopp.constantreminder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SilenceScenarioTest {

    @Test
    fun testSameDaySilence() {
        // Scenario: Monday 08:00 - 17:00
        val scenario = SilenceScenario(1, listOf(Calendar.MONDAY), 8, 0, 17, 0)
        
        // Monday 10:00 -> Should be silenced
        val monday10 = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
        }
        assertTrue("Monday 10:00 should be silenced", ReminderReceiver.isWithinScenario(monday10, scenario))

        // Monday 07:00 -> Should NOT be silenced
        val monday07 = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 0)
        }
        assertFalse("Monday 07:00 should NOT be silenced", ReminderReceiver.isWithinScenario(monday07, scenario))

        // Tuesday 10:00 -> Should NOT be silenced
        val tuesday10 = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.TUESDAY)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
        }
        assertFalse("Tuesday 10:00 should NOT be silenced", ReminderReceiver.isWithinScenario(tuesday10, scenario))
    }

    @Test
    fun testMidnightOverlapSilence() {
        // Scenario: Friday 22:00 - 07:00 (implicitly until Saturday 07:00)
        val scenario = SilenceScenario(1, listOf(Calendar.FRIDAY), 22, 0, 7, 0)

        // Friday 23:00 -> Should be silenced (starting today)
        val friday23 = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.FRIDAY)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 0)
        }
        assertTrue("Friday 23:00 should be silenced", ReminderReceiver.isWithinScenario(friday23, scenario))

        // Saturday 05:00 -> Should be silenced (started yesterday)
        val saturday05 = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
            set(Calendar.HOUR_OF_DAY, 5)
            set(Calendar.MINUTE, 0)
        }
        assertTrue("Saturday 05:00 should be silenced", ReminderReceiver.isWithinScenario(saturday05, scenario))

        // Saturday 08:00 -> Should NOT be silenced
        val saturday08 = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
        }
        assertFalse("Saturday 08:00 should NOT be silenced", ReminderReceiver.isWithinScenario(saturday08, scenario))
        
        // Sunday 05:00 -> Should NOT be silenced (not started on Saturday)
        val sunday05 = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            set(Calendar.HOUR_OF_DAY, 5)
            set(Calendar.MINUTE, 0)
        }
        assertFalse("Sunday 05:00 should NOT be silenced", ReminderReceiver.isWithinScenario(sunday05, scenario))
    }
}
