package dev.tigr.ttvattendance

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

/**
 * @author Tigermouthbear 1/26/21
 */
class TtvAttendance(val streamer: String, private val api: ApiHandler, private val fileName: String, private val delaySeconds: Long): ApplicationFeature<ApplicationCallPipeline, TtvAttendance, TtvAttendance> {
    private val objectMapper = ObjectMapper().registerModule(KotlinModule())
    private val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
    override val key: AttributeKey<TtvAttendance> = AttributeKey("TTVAttendance")

    private lateinit var dataPage: DataPage
    private var attendanceChart = AttendanceChart()
    internal var output = File("ttvattendance.json")
    private var date: String? = null
    
    override fun install(pipeline: ApplicationCallPipeline, configure: TtvAttendance.() -> Unit): TtvAttendance {
        return this.also(configure).also {
            // setup save file
            output = File(fileName)
            output.parentFile?.mkdirs()
            if(!output.exists()) {
                // write default to file if it doesnt exist
                output.createNewFile()
                output.writeBytes(objectMapper.writeValueAsBytes(attendanceChart))
            } else {
                try {
                    // read from file
                    attendanceChart = objectMapper.readValue(output)
                } catch(e: Exception) {
                    output.writeBytes(objectMapper.writeValueAsBytes(attendanceChart))
                }
            }

            // initialize data table
            dataPage = DataPage(this)
            dataPage.update()

            // setup timer to run at delay
            scheduledExecutor.scheduleWithFixedDelay({
                api.checkAuth()
                update()
            }, delaySeconds, delaySeconds, TimeUnit.SECONDS)
        }
    }

    fun respond(route: Route) {
        with(route) {
            get("/") {
                respondDataPage(dataPage)
            }
            route("api/v1") {
                route("streamers") {
                    get(streamer) {
                        call.respondFile(output)
                    }
                }
            }
        }
    }

    private fun update() {
        if(isLive()) {
            // add stream date if absent, dont change to current date bc the stream could go past midnight
            if(date == null) date = getDate()
            if(!attendanceChart.streams.contains(date)) attendanceChart.streams.add(date!!)
            val streamId = attendanceChart.streams.size - 1

            // get chatters
            val chatterResponse = api.getChatData(streamer)
            val chatters = chatterResponse.chatters

            // update largest size
            if(chatterResponse.chatter_count > attendanceChart.largest) attendanceChart.largest = chatterResponse.chatter_count

            // update chatter
            chatters.forEach { name, role ->
                val record = attendanceChart.chart.getOrPut(name, { AttendanceRecord(role) })
                if(!record.streams.contains(streamId)) record.streams.add(streamId)
                if(record.role != role) record.role = role
            }

            // save json
            output.writeBytes(objectMapper.writeValueAsBytes(attendanceChart))

            // update table
            dataPage.update()
        } else {
            date = null // used as a way to tell if the stream was offline
        }
    }

    private fun isLive(): Boolean {
        return api.getStreamData(streamer).data.isNotEmpty()
    }

    private fun getDate(): String {
        // make sure to get in correct timezone bc imma host this in Germany
        return Date.from(Instant.now()).toInstant().atZone(ZoneId.of("America/New_York")).format(DateTimeFormatter.BASIC_ISO_DATE)
    }

    fun getAttendance(): AttendanceChart = attendanceChart
}

// json save data classes
data class AttendanceChart(
    val streams: ArrayList<String> = arrayListOf(), // ordered list of stream dates, index is equal to stream id
    var largest: Int = 0, // largest chatter count
    val chart: HashMap<String, AttendanceRecord> = hashMapOf() // users and their record ex- name: { role: "", streams: [] }
)
data class AttendanceRecord(
    var role: String, // role / perms of user ex: moderator, vip
    val streams: ArrayList<Int> = arrayListOf() // ids of streams watched
)
