package dev.tigr.ttvattendance

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * @author Tigermouthbear 1/29/21
 */
class AttendanceDatabase(private val database: Database) {
    private val objectMapper = ObjectMapper().registerModule(KotlinModule())
    private val streamTable = StreamChartTable()
    private val chartTable = AttendanceChartTable()

    init {
        // create/read tables
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(streamTable, chartTable)
        }
    }

    fun update(date: String, chatters: Chatters) {
        val streamID = getStreamID(date) ?: return

        transaction(database) {
            chatters.forEach { user, role ->
                // find rows with name
                val query = chartTable.select{ chartTable.name eq user }

                // create row if none exist
                if(query.empty()) {
                    chartTable.insert {
                        it[chartTable.name] = user
                        it[chartTable.role] = role
                        it[chartTable.streams] = "[$streamID]" // json list of stream ids
                    }
                } else {
                    // update the row (should only be one)
                    query.forEach {
                        val list: ArrayList<Int> = objectMapper.readValue(it[chartTable.streams])
                        if(!list.contains(streamID)) list.add(streamID)
                        it[chartTable.streams] = objectMapper.writeValueAsString(list)
                        it[chartTable.role] = role
                    }
                }
            }
        }
    }

    // name, role, streams
    fun forEachRecord(call: (String, String, ArrayList<Int>) -> Unit) {
        transaction(database) {
            chartTable.selectAll().forEach {
                call.invoke(
                    it[chartTable.name],
                    it[chartTable.role],
                    objectMapper.readValue(it[chartTable.streams])
                )
            }
        }
    }

    fun getStreamsLength(): Int {
        var size = 0
        transaction(database) {
            streamTable.selectAll().forEach {
                if(it[streamTable.id] >= size) size = it[streamTable.id] + 1
            }
        }
        return size
    }

    private fun getStreamID(date: String): Int? {
        var id: Int? = null
        transaction(database) {
            // find id
            streamTable.select{ streamTable.date eq date }.forEach {
                id = it[streamTable.id]
                return@forEach
            }

            // if no id, create one
            if(id == null) {
                // find new id
                streamTable.selectAll().forEach {
                    id = 0
                    if(it[streamTable.id] >= id!!) id = it[streamTable.id] + 1
                }

                streamTable.insert {
                    it[streamTable.id] = id!!
                    it[streamTable.date] = date
                }
            }
        }
        return id
    }
}

class StreamChartTable: Table() {
    val id = integer("id")
    val date = varchar("date", 8)
}

class AttendanceChartTable: Table() {
    val name = varchar("name", 50)
    val role = varchar("role", 20)
    val streams = varchar("streams", 2000)
}