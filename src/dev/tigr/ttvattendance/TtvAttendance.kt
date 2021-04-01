package dev.tigr.ttvattendance

import io.ktor.routing.*
import org.jetbrains.exposed.sql.Database
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.*
import kotlin.math.max

/**
 * @author Tigermouthbear 1/26/21
 */
class TtvAttendance(val streamer: String, private val api: ApiHandler, database: Database, delay: Long = 300, min: Int = 3) {
    private val streamDateFormatter = DateTimeFormatterBuilder().parseCaseInsensitive()
        .appendValue(ChronoField.YEAR, 4).appendValue(ChronoField.MONTH_OF_YEAR, 2)
        .appendValue(ChronoField.DAY_OF_MONTH, 2).toFormatter()
    val attendanceCharts = AttendanceCharts(streamer, database)
    val attendanceSite = AttendanceSite(this, min).also { it.update() }
    private var date: String? = null

    init {
        // setup thread to run at delay
        Thread {
            while(!Thread.interrupted()) {
                try {
                    val length = update()
                    Thread.sleep(max((delay * 1000) - length, 0))
                } catch(e: Exception) {
                    e.printStackTrace()
                    Thread.sleep(60000) // sleep for a minute and try again
                }
            }
        }.start()
    }

    fun respond(route: Route, path: String = "/") {
        with(route) {
            get(path) {
                respond(attendanceSite)
            }
        }
    }

    private fun update(): Long {
        api.checkAuth()

        if(isLive()) {
            val start = System.currentTimeMillis()

            // add stream date if absent, dont change to current date bc the stream could go past midnight
            if(date == null) {
                date = getZonedDate().format(streamDateFormatter)
                println("[TTVA] $streamer went online")
            }

            // update database and website
            attendanceCharts.update(date!!, api.getChatData(streamer).chatters)
            attendanceSite.update()

            // calculate time it took and print
            val length = System.currentTimeMillis() - start
            val date = getZonedDate().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            println("[TTVA] Updated stream ${this.date} at $date in ${length/1000} seconds")
        } else {
            if(date != null) println("[TTVA] $streamer went offline")
            date = null // used as a way to tell if the stream was offline
        }

        return 0
    }

    private fun isLive(): Boolean {
        return api.getStreamData(streamer).data.isNotEmpty()
    }

    private fun getZonedDate(): ZonedDateTime {
        // make sure to get in correct timezone bc imma host this in Germany
        return Date.from(Instant.now()).toInstant().atZone(ZoneId.of("America/New_York"))
    }
}
