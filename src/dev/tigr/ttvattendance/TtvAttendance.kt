package dev.tigr.ttvattendance

import io.ktor.application.*
import io.ktor.routing.*
import io.ktor.util.*
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * @author Tigermouthbear 1/26/21
 */
class TtvAttendance(val streamer: String, private val api: ApiHandler, val database: AttendanceDatabase, private val delaySeconds: Long): ApplicationFeature<ApplicationCallPipeline, TtvAttendance, TtvAttendance> {
    private val dateTimeFormatter = DateTimeFormatterBuilder().parseCaseInsensitive().appendValue(ChronoField.YEAR, 4).appendValue(ChronoField.MONTH_OF_YEAR, 2).appendValue(ChronoField.DAY_OF_MONTH, 2).toFormatter()
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
                    api.checkAuth()
                    update()
                    Thread.sleep(max((delaySeconds * 1000) - (System.currentTimeMillis() - start), 0))
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
        if(isLive()) {
            // add stream date if absent, dont change to current date bc the stream could go past midnight
            if(date == null) date = getDate()

            // update database and datapage
            database.update(date!!, api.getChatData(streamer).chatters)
            dataPage.update()
            println("Updated stream $date!")
        } else {
            date = null // used as a way to tell if the stream was offline
        }
    }

    private fun isLive(): Boolean {
        return api.getStreamData(streamer).data.isNotEmpty()
    }

    private fun getDate(): String {
        // make sure to get in correct timezone bc imma host this in Germany
        return Date.from(Instant.now()).toInstant().atZone(ZoneId.of("America/New_York")).format(dateTimeFormatter)
    }
}
