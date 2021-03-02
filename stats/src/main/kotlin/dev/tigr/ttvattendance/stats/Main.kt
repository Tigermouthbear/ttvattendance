package dev.tigr.ttvattendance.stats

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// logs into sql server and generates stats for ttvattendance
// setup for postgres, but is easily changed. see jetbrains exposed documentation
fun main(args: Array<String>) {
    // set logger to only warnings
    System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "WARNING")

    if(args.size < 4 || args.size > 5) {
        println("Missing the correct arguments!\nUsage: java -jar stats.jar [streamer] [url] [user] [password] [range (optional)]")
        return
    }

    // data we are collecting
    var streamsLength = 0 // number of streams
    var streams: ArrayList<Int>? = null // ids of streams in range, null if no range
    var total = 0 // total number of viewers
    var active = 0 // total number of viewers who have shown up to half of the streams
    var dedicated = 0 // total number of viewers who have came to each of the streams

    // connect to database
    val database = Database.connect(args[1], driver = "org.postgresql.Driver", user = args[2], password = args[3])

    // get tables
    val streamTable = StreamChartTable(args[0])
    val chartTable = AttendanceChartTable(args[0])

    // get total streams in range
    val ranged = args.size == 5 && args[4].contains("-")
    if(ranged) streams = arrayListOf()
    val min: Int = if(ranged) args[4].split("-")[0].toInt() else 0
    val max: Int = if(ranged) args[4].split("-")[1].toInt() else Int.MAX_VALUE
    transaction(database) {
        streamTable.selectAll().forEach {
            val id = it[streamTable.id]
            if(id in min..max) {
                if(ranged) streams?.add(id)
                streamsLength++
            }
        }
    }

    // get total number of viewers
    transaction(database) {
        chartTable.selectAll().forEach {
            if(!ranged) {
                // check all
                val num = it[chartTable.streams].split(",").size + 1 // quick way to find amount attended
                if(num == streamsLength) dedicated++
                else if(num > streamsLength / 2) active++
                total++
            } else {
                // check ranged
                val attended: List<String> = it[chartTable.streams].replaceFirst("[", "")
                    .replaceFirst("]", "")
                    .split(",")
                var num = 0
                attended.forEach { if(streams!!.contains(it.toInt())) num++ }
                if(num == streamsLength) dedicated++
                else if(num > streamsLength / 2) active++
                if(num > 0) total++
            }
        }
    }

    // remove streamer from data, a bit hacky
    total--
    dedicated--

    // print data
    if(ranged) println("Showing statistics from ${args[0]} streams in range ${args[4]}...")
    else println("Showing statistics from all ${args[0]} streams...")
    println("Number of streams: $streamsLength")
    println("Number of unique viewers: $total")
    println("Number of active viewers(>50%): $active")
    println("Number of dedicated viewers(100%): $dedicated")
}

class StreamChartTable(streamer: String): Table("${streamer}_streams") {
    val id = integer("id")
    val date = varchar("date", 8)
}

class AttendanceChartTable(streamer: String): Table("${streamer}_attendance") {
    val name = varchar("name", 50).uniqueIndex()
    val role = varchar("role", 20)
    val streams = varchar("streams", 2000)
}