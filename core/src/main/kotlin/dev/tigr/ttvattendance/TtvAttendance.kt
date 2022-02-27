package dev.tigr.ttvattendance

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import freemarker.cache.ClassTemplateLoader
import freemarker.template.Configuration
import io.ktor.http.content.*
import io.ktor.routing.*
import org.jetbrains.exposed.sql.Database
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.*
import kotlin.math.max

/**
 * @author Tigermouthbear 1/26/21
 * updated on 2/19/22
 */
class TtvAttendance(private val streamer: String, private val api: ApiHandler, database: Database, template: String = "ttva.ftl", delay: Long = 120, private val min: Int = 1) {
    private val objectMapper = jacksonObjectMapper()
    private val freemarker = Configuration(Configuration.DEFAULT_INCOMPATIBLE_IMPROVEMENTS).also {
        it.templateLoader = ClassTemplateLoader(this::class.java.classLoader, "")
    }

    private val folder = File(streamer).also { if(!it.exists()) it.mkdir() }
    private val data = File(folder, "data").also { if(!it.exists()) it.mkdir() }
    private val rows = arrayListOf<File>()
    private val index = File(folder, "index.html").also { if(!it.exists()) it.createNewFile() }

    private val attendance = AttendanceCharts(streamer, delay, database)
    private var date: Int? = null

    private var running = true

    init {
        // fill in template at start
        freemarker.template(template, mapOf(
            "streamer" to streamer
        ), index)

        // setup thread to run at delay
        val thread = Thread {
            // update attendance site on start
            updateSite()

            while(running && !Thread.interrupted()) {
                try {
                    val length = update()
                    Thread.sleep(max((delay * 1000) - length, 0))
                } catch(ignored: InterruptedException) {
                } catch(e: Exception) {
                    e.printStackTrace()
                    Thread.sleep(60000) // sleep for a minute and try again
                }
            }

            println("[TTVA] Stopped tracking")
        }
        thread.start()

        // stop and join thread on shutdown
        Runtime.getRuntime().addShutdownHook(Thread {
            running = false
            thread.interrupt()
            thread.join()
        })
    }

    fun route(route: Route, path: String = "/") {
        with(route) {
            static(path) {
                files(folder)
                default(index)
            }
        }
    }

    private fun update(): Long {
        val start = System.currentTimeMillis()

        api.checkAuth()

        if(isLive()) {
            // add stream date if absent, dont change to current date bc the stream could go past midnight
            if(date == null) {
                date = getDateInt()
                println("[TTVA] $streamer went online")
            }

            // update database and site
            attendance.update(date!!, api.getChatData(streamer).chatters)
            updateSite()

            // calculate time it took and print
            val length = System.currentTimeMillis() - start
            println("[TTVA] Updated stream $date in ${length/1000} seconds")
            return length
        } else {
            if(date != null) println("[TTVA] $streamer went offline")
            date = null // used as a way to tell if the stream was offline
            return System.currentTimeMillis() - start
        }
    }

    private fun updateSite() {
        var num = 0
        val temps = arrayListOf<File>()
        attendance.forEachDescending(10000, min) { group ->
            val temp = File(data, "${num+1}.temp.json")
            temp.createNewFile()
            temps.add(temp)
            objectMapper.writeValue(temp, group)
            num++
        }

        // add size to data
        for(i in 0 until num) {
            temps[i].inputStream().use { fis ->
                (rows.getOrNull(i) ?: File(data, "${i+1}.json").also { rows.add(i, it) }).outputStream().use { os ->
                    // write pagination json data
                    os.write("""{"last_page":$num,"data":""".toByteArray())

                    // copy row data
                    var len: Int
                    val buffer = ByteArray(1024)
                    while(fis.read(buffer).also { len = it } != -1) os.write(buffer, 0, len)

                    // close json object
                    os.write(0x7D)
                }
            }
        }

        // remove unused files
        if(rows.size > num) {
            for(i in num until rows.size)
                rows[i].delete()
        }

        // remove temp files
        temps.forEach { it.delete() }
    }

    private fun isLive(): Boolean {
        return api.getStreamData(streamer).data.isNotEmpty()
    }

    private val streamDateFormatter = DateTimeFormatterBuilder().parseCaseInsensitive()
        .appendValue(ChronoField.YEAR, 4).appendValue(ChronoField.MONTH_OF_YEAR, 2)
        .appendValue(ChronoField.DAY_OF_MONTH, 2).toFormatter()
    private fun getDateInt(): Int {
        // make sure to get in correct timezone bc imma host this in Germany
        return Date.from(Instant.now()).toInstant().atZone(ZoneId.of("America/New_York")).format(streamDateFormatter).toInt()
    }

    private fun Configuration.template(template: String, data: Any, out: File) {
        out.writer().use {
            getTemplate(template).process(data, it)
        }
    }
}
