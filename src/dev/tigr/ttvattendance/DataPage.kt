package dev.tigr.ttvattendance

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.util.pipeline.*
import java.io.File
import java.lang.StringBuilder
import kotlin.math.min

/**
 * @author Tigermouthbear 1/27/21
 */
class DataPage(private val ttvAttendance: TtvAttendance, private val minPresent: Int) {
    val file = File(ttvAttendance.output.path.replace(".json", ".html"))
    private val prefix: ByteArray = """
    <!DOCTYPE html>
    <html>
        <head>
            <title>${ttvAttendance.streamer} - TTVAttendance</title>
            <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.0.0-beta1/dist/css/bootstrap.min.css" rel="stylesheet" integrity="sha384-giJF6kkoqNQ00vy+HMDP7azOuL0xtbfIcaT9wjKHr8RbDVddVHyTfAAsrekwKmP1" crossorigin="anonymous">
            <style>
                h1, p {
                    text-align: center;
                }
    
                .top-30 {
                    top: 30%;
                }
                
                img {
                    float: left;
                }
            </style>
        </head>
    
        <body>
            <div class="position-absoulte">
                <div class="position-absolute top-0 start-50 translate-middle-x">
                    <h1 class="display-3"><b>Twitch Attendance Chart</b></h1>
                    <h1 class="display-4">${ttvAttendance.streamer}</h1>
                    <p>updates every 5 mins</p>
                </div>
            </div>
            
            <div class="position-absolute">
                <div class="position-absolute top-0 start-0">
                    <a href="https://github.com/Tigermouthbear/ttvattendance"><img src="https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png" width="100px"></a>
                </div>
            </div>
    
            <div class="position-absoulte" id="center">
                <div class="position-absolute top-30 start-50 translate-middle-x">
                    <table class="table">
                        <thead>
                            <tr>
                                <th>#</th>
                                <th>Name</th>
                                <th>Role</th>
                                <th>Present</th>
                                <th>Absent</th>
                            </tr>
                        </thead>
                        <tbody>
    """.trimIndent().toByteArray()
    private val suffix: ByteArray = """
                        </tbody>
                    </table>
                </div>
            </div>
        </body>
    </html>
    """.trimIndent().toByteArray()

    fun update() {
        val out = StringBuilder()

        // sort by rank
        val ranked = hashMapOf<Int, ArrayList<Pair<String, AttendanceRecord>>>()
        ttvAttendance.getAttendance().chart.forEach { entry ->
            val len = entry.value.streams.size
            if(!ranked.contains(len)) ranked[len] = arrayListOf()
            ranked[len]!!.add(Pair(entry.key, entry.value))
        }
        val sorted = ranked.toSortedMap()
        val top = sorted.lastKey()

        // add rows
        val size = ttvAttendance.getAttendance().streams.size
        val min = min(minPresent, size)
        for(key in sorted.keys.reversed()) {
            val value = sorted[key] ?: continue
            for(pair in value) {
                val present = pair.second.streams.size
                if(present < min) break // break if the entry/rank doesnt meet minimum requirements
                val absent = size - present
                out.append("<tr>")
                out.append("<td>${top - key + 1}</td>")
                out.append("<td><a href=\"https://www.twitch.tv/${pair.first}\">${pair.first}</td>")
                out.append("<td>${pair.second.role}</td>")
                out.append("<td>$present</td>")
                out.append("<td>$absent</td>")
                out.append("</tr>")
            }
        }

        // write to file
        if(!file.exists()) file.createNewFile()
        val os = file.outputStream()
        os.write(prefix)
        os.write(out.toString().toByteArray())
        os.write(suffix)
        os.close()
    }
}

suspend fun PipelineContext<Unit, ApplicationCall>.respondDataPage(dataPage: DataPage) {
    call.respondFile(dataPage.file)
}
