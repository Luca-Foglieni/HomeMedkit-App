package ru.application.homemedkit.receivers

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AlarmManager.RTC_WAKEUP
import android.app.PendingIntent.*
import android.content.Context
import android.content.Intent
import ru.application.homemedkit.data.MedicineDatabase
import ru.application.homemedkit.utils.ALARM_ID
import ru.application.homemedkit.utils.Formatter
import ru.application.homemedkit.utils.Preferences
import ru.application.homemedkit.utils.extensions.canScheduleExactAlarms
import java.time.LocalTime
import java.time.ZonedDateTime

class AlarmSetter private constructor(private val context: Context) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    private val database = MedicineDatabase.getInstance(context)

    // A new Intent for every PendingIntent: receivers run in parallel on Dispatchers.Default,
    // and a shared Intent could get another alarm's id between putExtra() and getBroadcast()
    private fun alarmPending(takenId: Long, flags: Int = FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE) =
        getBroadcast(context, takenId.toInt(), Intent(context, AlarmReceiver::class.java).putExtra(ALARM_ID, takenId), flags)

    private fun preAlarmPending(alarmId: Long, flags: Int = FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE) =
        getBroadcast(context, alarmId.toInt(), Intent(context, PreAlarmReceiver::class.java).putExtra(ALARM_ID, alarmId), flags)

    fun setAlarm(takenId: Long, trigger: Long) {
        val pending = alarmPending(takenId)

        with(manager) {
            if (context.canScheduleExactAlarms()) {
                val preferences = Preferences.getInstance(context)

                if (preferences.useAlarmClock) {
                    setAlarmClock(AlarmManager.AlarmClockInfo(trigger, pending), pending)
                } else {
                    setExactAndAllowWhileIdle(RTC_WAKEUP, trigger, pending)
                }
            } else {
                setAndAllowWhileIdle(RTC_WAKEUP, trigger, pending)
            }
        }
    }

    suspend fun setPreAlarm(intakeId: Long) {
        val alarm = database.alarmDAO().getNextByIntakeId(intakeId) ?: return
        val preTrigger = alarm.trigger - 1800000L

        val pending = preAlarmPending(alarm.alarmId)

        with(manager) {
            if (context.canScheduleExactAlarms()) {
                val preferences = Preferences.getInstance(context)

                if (preferences.useAlarmClock) {
                    setAlarmClock(AlarmManager.AlarmClockInfo(preTrigger, pending), pending)
                } else {
                    setExactAndAllowWhileIdle(RTC_WAKEUP, preTrigger, pending)
                }
            } else {
                setAndAllowWhileIdle(RTC_WAKEUP, preTrigger, pending)
            }
        }
    }

    suspend fun removeAlarm(intakeId: Long) {
        val nextAlarm = database.alarmDAO().getNextByIntakeId(intakeId) ?: return

        with(manager) {
            cancel(preAlarmPending(nextAlarm.alarmId))
            cancel(alarmPending(nextAlarm.alarmId))
        }
    }

    // Re-arms every intake that still has scheduled alarms, not only those with already expired ones:
    // after a reboot no alarm is pending, so an intake whose next alarm is in the future would stay silent
    suspend fun resetAll() = database.alarmDAO().getIntakeIds().forEach { setPreAlarm(it) }

    suspend fun cancelAll() = database.alarmDAO().getAll().forEach {
        with(manager) {
            cancel(preAlarmPending(it.alarmId, FLAG_CANCEL_CURRENT or FLAG_IMMUTABLE))
            cancel(alarmPending(it.alarmId, FLAG_CANCEL_CURRENT or FLAG_IMMUTABLE))
        }
    }

    fun checkExpiration(check: Boolean) {
        val broadcast = getBroadcast(
            context,
            81000,
            Intent(context, ExpirationReceiver::class.java),
            FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT
        )

        with(manager) {
            if (check) {
                val now = ZonedDateTime.now(Formatter.ZONE)
                val noonToday = ZonedDateTime.of(now.toLocalDate(), LocalTime.NOON, Formatter.ZONE)
                val nextNoon = if (now.isBefore(noonToday)) noonToday else noonToday.plusDays(1)
                val nextTime = nextNoon.toInstant().toEpochMilli()

                if (context.canScheduleExactAlarms()) {
                    val preferences = Preferences.getInstance(context)

                    if (preferences.useAlarmClock) {
                        setAlarmClock(AlarmManager.AlarmClockInfo(nextTime, broadcast), broadcast)
                    } else {
                        setExactAndAllowWhileIdle(RTC_WAKEUP, nextTime, broadcast)
                    }
                } else {
                    setAndAllowWhileIdle(RTC_WAKEUP, nextTime, broadcast)
                }
            } else {
                cancel(broadcast)
            }
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: AlarmSetter? = null

        fun getInstance(context: Context) = INSTANCE ?: synchronized(this) {
            AlarmSetter(context.applicationContext).also { INSTANCE = it }
        }
    }
}