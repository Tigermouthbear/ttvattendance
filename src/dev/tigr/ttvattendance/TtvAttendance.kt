package dev.tigr.ttvattendance

import io.ktor.application.*
import io.ktor.routing.*
import io.ktor.util.*
import kotlinx.coroutines.*
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
class TtvAttendance(val streamer: String, private val api: ApiHandler, val database: AttendanceDatabase, private val delaySeconds: Long): ApplicationFeature<ApplicationCallPipeline, TtvAttendance, TtvAttendance> {
    private val streamDateFormatter = DateTimeFormatterBuilder().parseCaseInsensitive().appendValue(ChronoField.YEAR, 4).appendValue(ChronoField.MONTH_OF_YEAR, 2).appendValue(ChronoField.DAY_OF_MONTH, 2).toFormatter()
    override val key: AttributeKey<TtvAttendance> = AttributeKey("TTVAttendance")

    private lateinit var dataPage: DataPage
    private var date: String? = null
    
    override fun install(pipeline: ApplicationCallPipeline, configure: TtvAttendance.() -> Unit): TtvAttendance {
        return this.also(configure).also {
            // initialize data table
            dataPage = DataPage(this)
            dataPage.update()

            // setup thread to run at delay
            Thread {
                while(!Thread.interrupted()) {
                    val start = System.currentTimeMillis()
                    update()
                    val length = System.currentTimeMillis() - start
                    val date = getZonedDate().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    println("[TTVA] Updated stream $date at $date in ${length/1000} seconds")
                    Thread.sleep(max((delaySeconds * 1000) - length, 0))
                }
            }.start()
        }
    }

    fun respond(route: Route) {
        with(route) {
            get("/") {
                respond(dataPage)
            }
        }
    }

    private fun update() {
        api.checkAuth()

        if(isLive()) {
            // add stream date if absent, dont change to current date bc the stream could go past midnight
            if(date == null) date = getZonedDate().format(streamDateFormatter)

            // update database and datapage
            database.update(date!!, api.getChatData(streamer).chatters)
            dataPage.update()
        } else {
            date = null // used as a way to tell if the stream was offline
        }
    }

    private fun isLive(): Boolean {
        return api.getStreamData(streamer).data.isNotEmpty()
    }

    private fun getZonedDate(): ZonedDateTime {
        // make sure to get in correct timezone bc imma host this in Germany
        return Date.from(Instant.now()).toInstant().atZone(ZoneId.of("America/New_York"))
    }
}
