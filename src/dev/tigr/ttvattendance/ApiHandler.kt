package dev.tigr.ttvattendance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.net.URL
import java.time.Duration
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

/**
 * @author Tigermouthbear 1/27/21
 */
abstract class ApiHandler(protected val clientID: String, private val clientSecret: String, fileName: String) {
    protected val objectMapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule())

    protected var auth: Auth
    private var authFile: File = File(fileName)

    // read authtoken from file
    init {
        authFile.parentFile?.mkdirs()
        if(!authFile.exists()) {
            // write default to file if it doesnt exist
            authFile.createNewFile()
            auth = genOAuthToken()
            saveAuth()
        } else {
            try {
                // read from file
                auth = objectMapper.readValue(authFile)
            } catch(e: Exception) {
                auth = genOAuthToken()
                saveAuth()
            }
        }
    }

    abstract fun getStreamData(streamer: String): StreamerResponse
    abstract fun getChatData(streamer: String): ChatterResponse

    private fun genOAuthToken(): Auth {
        val authResponse: AuthResponse = objectMapper.readValue((URL("https://id.twitch.tv/oauth2/token?client_id=$clientID&client_secret=$clientSecret&grant_type=client_credentials").openConnection() as HttpsURLConnection).also {
            it.addRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/88.0.4324.96 Safari/537.36")
            it.requestMethod = "POST"
            it.doOutput = true
        }.inputStream.readBytes())
        return Auth(authResponse.access_token, authResponse.expires_in, Instant.now().toString(), authResponse.token_type)
    }

    // needs to be called periodically to keep auth token refreshed
    fun checkAuth() {
        // calculate difference in time
        val genInstant = Instant.parse(auth.generated_at)
        val nowInstant = Instant.now()
        val duration = Duration.between(genInstant, nowInstant)

        // regen at halfway through
        if(duration.isNegative || duration.isZero || duration.seconds > auth.expires_in / 2) {
            auth = genOAuthToken()
            saveAuth()
        }
    }

    private fun saveAuth() {
        authFile.writeBytes(objectMapper.writeValueAsBytes(auth))
    }
}

// json api data classes
data class Auth(val token: String, val expires_in: Int, val generated_at: String, val token_type: String)
data class AuthResponse(val access_token: String, val expires_in: Int, val token_type: String)

data class ChatterResponse(val _links: JsonNode, val chatter_count: Int, val chatters: Chatters)
data class Chatters(val broadcaster: List<String>, val vips: List<String>, val moderators: List<String>, val staff: List<String>, val admins: List<String>,
                    val global_mods: List<String>, val viewers: List<String>) {
    fun forEach(loop: (String, String) -> Unit) {
        broadcaster.forEach { name -> loop(name, "broadcaster") }
        vips.forEach { name -> loop(name, "vips") }
        moderators.forEach { name -> loop(name, "moderators") }
        staff.forEach { name -> loop(name, "staff") }
        admins.forEach { name -> loop(name, "admins") }
        global_mods.forEach { name -> loop(name, "global_mods") }
        viewers.forEach { name -> loop(name, "viewers") }
    }
}

data class StreamerResponse(val data: List<JsonNode>, val pagination: JsonNode)

class HttpApiHandler(clientID: String, clientSecret: String, fileName: String): ApiHandler(clientID, clientSecret, fileName) {
    override fun getStreamData(streamer: String): StreamerResponse {
        return objectMapper.readValue(URL("https://api.twitch.tv/helix/streams?user_login=$streamer").openConnection().also {
            it.addRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/88.0.4324.96 Safari/537.36")
            it.addRequestProperty("client-id", clientID)
            it.addRequestProperty("Authorization", "Bearer ${auth.token}")
        }.getInputStream())
    }

    override fun getChatData(streamer: String): ChatterResponse {
        return objectMapper.readValue(URL("https://tmi.twitch.tv/group/user/$streamer/chatters").openConnection().also {
            it.addRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/88.0.4324.96 Safari/537.36")
        }.getInputStream())
    }
}