package dev.tigr.ttvattendance

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * @author Tigermouthbear 1/29/21
 * updated on 2/19/22
 */
class AttendanceCharts(streamer: String, private val delay: Long, private val database: Database) {
    private val streamTable = StreamTable(streamer)
    private val attendanceTable = AttendanceTable(streamer)

    init {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(streamTable, attendanceTable)
        }
    }

    fun update(date: Int, chatters: Chatters) {
        val chatterCount = chatters.size()
        transaction(database) {
            val stream = streamTable.select { streamTable.date eq date }.firstOrNull()
            if(stream == null) {
                streamTable.insert {
                    it[streamTable.date] = date
                    it[streamTable.avg] = chatterCount
                }
            } else {
                val numAvg = stream[streamTable.numAvg]
                streamTable.update({ streamTable.date eq date }) {
                    it[streamTable.avg] = ((stream[streamTable.avg] * numAvg) + chatterCount) / (numAvg + 1)
                    it[streamTable.numAvg] = numAvg + 1
                }
            }

            chatters.forEach { user, role ->
                val row = attendanceTable.select { attendanceTable.name eq user }.firstOrNull()
                if(row != null) {
                    attendanceTable.update({ attendanceTable.name eq user }) {
                        if(row[attendanceTable.last] != date) {
                            val streams = row[attendanceTable.streams] + date
                            it[attendanceTable.role] = role
                            it[attendanceTable.streams] = streams
                            it[attendanceTable.present] = streams.size
                            it[attendanceTable.last] = date
                        }
                        it[attendanceTable.time] = row[attendanceTable.time] + delay
                    }
                } else {
                    attendanceTable.insert {
                        it[attendanceTable.name] = user
                        it[attendanceTable.role] = role
                        it[attendanceTable.streams] = arrayOf(date)
                        it[attendanceTable.present] = 1
                        it[attendanceTable.last] = date
                    }
                }
            }
        }
    }

    fun forEachDescending(batch: Int, min: Int, callback: (Iterable<AttendanceUser>) -> Unit) {
        val total = getTotalStreams()
        val list = arrayListOf<AttendanceUser>()
        var curr = 1

        transaction(database) {
            val range = if(min >= total) total..total else total downTo min
            for(i in range) {
                attendanceTable.selectBatched { attendanceTable.present eq i }.forEach { it.forEach { row ->
                    val present = row[attendanceTable.present]
                    list.add(AttendanceUser(row[attendanceTable.name], row[attendanceTable.role].text, present, total - present, row[attendanceTable.time] / 60))

                    if(curr++ > batch) {
                        callback(list)
                        list.clear()
                        curr = 1
                    }
                }}
            }
            if(list.size != 0) callback(list)
        }
    }

    private fun getTotalStreams(): Int {
        var i = 0
        transaction(database) {
            i = streamTable.selectAll().count().toInt() // this translates to COUNT(*)
        }
        return i
    }
}

class StreamTable(streamer: String): Table("${streamer}_streams") {
    val id = integer("id").autoIncrement()
    val date = integer("date").uniqueIndex()
    val avg = integer("avg")
    val numAvg = integer("numavg").default(1)
}

class AttendanceTable(streamer: String): Table("${streamer}_attendance") {
    val id = integer("id").autoIncrement() // for batch selection
    val name = varchar("name", 50).uniqueIndex()
    val role = enumeration("role", ChatRole::class)
    val streams = array<Int>("streams", IntegerColumnType()) // array of dates formatted like yyyymmdd
    val present = integer("present") // amount of streams attended, size of streams column
    val last = integer("last") // the last stream attended
    val time = long("time").default(0L)
}

data class AttendanceUser(val name: String, val role: String, val present: Int, val absent: Int, val mins: Long)
