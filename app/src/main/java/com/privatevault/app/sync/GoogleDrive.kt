package com.privatevault.app.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Google Drive for cloud sync: the four [CloudDrive] operations and the account address, with the
 * same endpoints, parameters, multipart body and error mapping as Windows `google_drive.rs`.
 * Nuvori asks for one permission, `drive.appdata`: a hidden folder only Nuvori can read. It cannot
 * see any other file in the user's Drive. Nuvori has no server; requests go straight to Google.
 */
internal class GoogleDrive(private val access: DriveAccess) : CloudDrive {
    private class Unauthorized : Exception()

    /** The email address of the signed-in Google account. */
    fun account(): String {
        val json = send { bearer -> readJson(call("GET", "$ABOUT_URL?fields=${percent("user(emailAddress)")}", bearer)) }
        return runCatching { json.getAsJsonObject("user").get("emailAddress").asString }.getOrNull() ?: "Google account"
    }

    override fun list(modifiedAfter: String?): List<CloudFile> {
        val query = if (modifiedAfter != null) "trashed = false and modifiedTime > '${modifiedAfter.replace("'", "")}'"
            else "trashed = false"
        val files = mutableListOf<CloudFile>()
        var page = ""
        while (true) {
            val url = "$FILES_URL?spaces=appDataFolder&pageSize=1000&q=${percent(query)}" +
                "&fields=${percent("nextPageToken,files($FILE_FIELDS)")}" +
                if (page.isNotEmpty()) "&pageToken=${percent(page)}" else ""
            val json = send { bearer -> readJson(call("GET", url, bearer)) }
            json.getAsJsonArray("files")?.forEach { element ->
                runCatching { element.asJsonObject }.getOrNull()?.let(::parseFile)?.let(files::add)
            }
            val next = runCatching { json.get("nextPageToken")?.asString }.getOrNull()
            // A folder this large means something is wrong; stop instead of listing forever.
            if (next.isNullOrEmpty() || files.size >= 200_000) return files
            page = next
        }
    }

    override fun download(id: String, maxBytes: Long, read: (InputStream) -> Unit) {
        val connection = send { bearer -> call("GET", "$FILES_URL/${percent(id)}?alt=media", bearer) }
        try {
            if (connection.contentLengthLong > maxBytes) throw CloudException(CloudError.OTHER, "file larger than expected")
            val input = try { connection.inputStream } catch (_: IOException) { throw CloudException(CloudError.OFFLINE) }
            NetworkInput(input, maxBytes).use(read)
        } finally { connection.disconnect() }
    }

    override fun upload(name: String, size: Long, content: InputStream): CloudFile {
        if (size <= SINGLE_REQUEST_BYTES) {
            val bytes = content.readBytes()
            require(bytes.size.toLong() == size) { "Upload size changed" }
            val boundary = "nuvori-" + UUID.randomUUID().toString().replace("-", "")
            val body = multipartBody(boundary, name, bytes)
            return send { bearer ->
                fileFrom(call("POST", "$UPLOAD_URL?uploadType=multipart&fields=${percent(FILE_FIELDS)}", bearer,
                    mapOf("Content-Type" to "multipart/related; boundary=$boundary"), body))
            }
        }
        // Resumable upload: ask for an upload address, then send the bytes there in one stream.
        val metadata = metadata(name).toByteArray(Charsets.UTF_8)
        val session = send { bearer ->
            call("POST", "$UPLOAD_URL?uploadType=resumable&fields=${percent(FILE_FIELDS)}", bearer,
                mapOf("Content-Type" to "application/json; charset=UTF-8",
                    "X-Upload-Content-Type" to "application/octet-stream", "X-Upload-Content-Length" to size.toString()),
                metadata)
        }
        val address: String? = try { session.getHeaderField("Location") } finally { session.disconnect() }
        val location = address ?: throw CloudException(CloudError.OTHER, "no upload address")
        // The upload address carries its own authorization, and a stream cannot be sent twice.
        val connection = open("PUT", location, null)
        try {
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(size)
            connection.outputStream.use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = size
                while (remaining > 0) {
                    val count = content.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw CloudException(CloudError.OTHER, "upload source ended early")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
            check(connection, connection.responseCode)
            return fileFrom(connection)
        } catch (_: IOException) { throw CloudException(CloudError.OFFLINE) }
        catch (_: Unauthorized) { throw CloudException(CloudError.AUTH, "HTTP 401") }
        finally { connection.disconnect() }
    }

    override fun replace(id: String, content: ByteArray): CloudFile = send { bearer ->
        fileFrom(call("PATCH", "$UPLOAD_URL/${percent(id)}?uploadType=media&fields=${percent(FILE_FIELDS)}", bearer,
            mapOf("Content-Type" to "application/octet-stream"), content))
    }

    /** Deletes a file in the app folder (Drive backups only; sync never deletes). A file already gone counts as deleted. */
    fun delete(id: String) {
        try { send { bearer -> call("DELETE", "$FILES_URL/${percent(id)}", bearer).disconnect() } }
        catch (failure: CloudException) { if (failure.error != CloudError.NOT_FOUND) throw failure }
    }

    /** Runs a request with the access token. A rejected token is cleared and renewed once. */
    private fun <T> send(request: (String) -> T): T {
        val token = access.token()
        return try { request("Bearer $token") }
        catch (_: Unauthorized) {
            access.refresh(token)
            try { request("Bearer ${access.token()}") } catch (_: Unauthorized) { throw CloudException(CloudError.AUTH, "HTTP 401") }
        }
    }

    /** Sends one request and returns the open connection of a successful answer. */
    private fun call(method: String, url: String, bearer: String, headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null): HttpURLConnection {
        val connection = open(method, url, bearer)
        try {
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            check(connection, connection.responseCode)
            return connection
        } catch (failure: Exception) {
            connection.disconnect()
            throw if (failure is IOException) CloudException(CloudError.OFFLINE) else failure
        }
    }

    private fun check(connection: HttpURLConnection, code: Int) {
        if (code in 200..299) return
        val text = runCatching { connection.errorStream?.use { String(readAtMost(it, 64 * 1024), Charsets.UTF_8) } }
            .getOrNull().orEmpty()
        if (code == 401) throw Unauthorized()
        throw CloudException(classify(code, text), "HTTP $code")
    }

    private fun readJson(connection: HttpURLConnection): JsonObject = try {
        val bytes = connection.inputStream.use { readAtMost(it, MAX_JSON_BYTES + 1) }
        if (bytes.size > MAX_JSON_BYTES) throw CloudException(CloudError.OTHER, "answer too large")
        runCatching { JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject }.getOrNull()
            ?: throw CloudException(CloudError.OTHER, "unexpected answer")
    } catch (_: IOException) { throw CloudException(CloudError.OFFLINE) }
    finally { connection.disconnect() }

    private fun fileFrom(connection: HttpURLConnection): CloudFile =
        parseFile(readJson(connection)) ?: throw CloudException(CloudError.OTHER, "unexpected answer")

    /** Read failures become [CloudError.OFFLINE]; more than [limit] bytes is [CloudError.OTHER]. */
    private class NetworkInput(private val input: InputStream, private val limit: Long) : InputStream() {
        private var total = 0L
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = try { input.read(buffer, offset, length) } catch (_: IOException) { throw CloudException(CloudError.OFFLINE) }
            if (count > 0) {
                total += count
                if (total > limit) throw CloudException(CloudError.OTHER, "file larger than expected")
            }
            return count
        }
        override fun close() { runCatching { input.close() } }
    }

    internal companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        private const val ABOUT_URL = "https://www.googleapis.com/drive/v3/about"
        private const val FILE_FIELDS = "id,name,size,modifiedTime"
        /** A file up to this size goes up in one request; a larger one uses a resumable upload. */
        private const val SINGLE_REQUEST_BYTES = 4L * 1024 * 1024
        private const val MAX_JSON_BYTES = 64 * 1024 * 1024

        private fun percent(text: String): String = URLEncoder.encode(text, "UTF-8")

        /** `InputStream.readNBytes` needs API 33; this app runs from API 29. */
        private fun readAtMost(input: InputStream, limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (output.size() < limit) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private fun open(method: String, url: String, bearer: String?): HttpURLConnection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                useCaches = false
                try { requestMethod = method }
                catch (_: ProtocolException) {
                    // Some HttpURLConnection builds refuse PATCH; Google APIs accept this override.
                    requestMethod = "POST"
                    setRequestProperty("X-HTTP-Method-Override", method)
                }
                setRequestProperty("User-Agent", "Nuvori")
                if (bearer != null) setRequestProperty("Authorization", bearer)
            }

        private fun metadata(name: String): String = JsonObject().apply {
            addProperty("name", name)
            add("parents", com.google.gson.JsonArray().apply { add("appDataFolder") })
        }.toString()

        /** `multipart/related` body for a new file in the app folder. */
        fun multipartBody(boundary: String, name: String, content: ByteArray): ByteArray =
            ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metadata(name)}\r\n" +
                "--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8) +
                content + "\r\n--$boundary--".toByteArray(Charsets.UTF_8)

        /** The Drive API sends 64-bit numbers as strings. */
        fun parseFile(json: JsonObject): CloudFile? = runCatching {
            val size = json.get("size")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let { value ->
                if (value.isString) value.asString.toLongOrNull() else value.asLong
            } ?: 0L
            CloudFile(json.get("id").asString, json.get("name").asString, size, json.get("modifiedTime").asString)
        }.getOrNull()

        /** Maps a Google error answer to what the user can do about it. */
        fun classify(code: Int, body: String): CloudError = when {
            code == 401 -> CloudError.AUTH
            code == 400 && "invalid_grant" in body -> CloudError.AUTH
            code == 403 && "storageQuotaExceeded" in body -> CloudError.QUOTA
            code == 403 && "ateLimitExceeded" in body -> CloudError.BUSY
            code == 404 -> CloudError.NOT_FOUND
            code == 429 || code in 500..599 -> CloudError.BUSY
            else -> CloudError.OTHER
        }
    }
}

/** An access token for one pass. [refresh] drops a token Google rejected. */
internal interface DriveAccess {
    fun token(): String
    fun refresh(stale: String)
}

/**
 * Google sign-in through Google Identity Services authorization.
 *
 * No client ID or secret is in this code. Google matches the app by its application ID and signing
 * certificate against an "Android" OAuth client: `com.application.private_vault` for release builds
 * and `com.application.private_vault.debug` for debug builds (each with its own certificate SHA-1).
 * That client must be in the same Google Cloud project as the Windows "Desktop" client, because the
 * hidden app folder belongs to the Cloud project: clients of two projects see two different folders.
 */
internal object GoogleDriveSignIn {
    sealed interface Outcome {
        data class Token(val accessToken: String) : Outcome
        /** Google must show its consent or account screen first. */
        data class NeedsUser(val intent: PendingIntent) : Outcome
        data class Failed(val message: String) : Outcome
        data object Canceled : Outcome
    }

    const val NO_PLAY_SERVICES = "Google Drive sync needs Google Play services, which are missing or out of date on this device. Nothing changed."

    private fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(GoogleDrive.SCOPE))).build()

    fun available(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /** Blocking; call off the main thread. */
    fun authorize(context: Context): Outcome {
        if (!available(context)) return Outcome.Failed(NO_PLAY_SERVICES)
        return outcome { Tasks.await(Identity.getAuthorizationClient(context).authorize(request()), 60, TimeUnit.SECONDS) }
    }

    /** Reads the answer of Google's screen that [Outcome.NeedsUser] opened. */
    fun fromIntent(context: Context, data: Intent?): Outcome {
        if (data == null) return Outcome.Canceled
        return outcome { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data) }
    }

    /** Best effort: tells Google Play services to forget [token] so the next request gets a new one. */
    fun clearToken(context: Context, token: String) {
        runCatching {
            Tasks.await(Identity.getAuthorizationClient(context)
                .clearToken(ClearTokenRequest.builder().setToken(token).build()), 30, TimeUnit.SECONDS)
        }
    }

    private fun outcome(result: () -> AuthorizationResult): Outcome = try {
        val answer = result()
        val pending = answer.pendingIntent
        val token = answer.accessToken
        when {
            answer.hasResolution() && pending != null -> Outcome.NeedsUser(pending)
            !token.isNullOrEmpty() -> Outcome.Token(token)
            else -> Outcome.Failed("Google didn't allow access to Drive. Try again.")
        }
    } catch (failure: Exception) {
        val api = (failure as? ExecutionException)?.cause as? ApiException ?: failure as? ApiException
        // The status code only; never the token or account. It tells a setup problem from a cancel.
        android.util.Log.w("NuvoriDrive", "Google authorization failed: status=${api?.statusCode} ${failure.javaClass.simpleName}")
        when {
            failure is TimeoutException -> Outcome.Failed(CloudError.OFFLINE.message)
            api?.statusCode == CommonStatusCodes.NETWORK_ERROR -> Outcome.Failed(CloudError.OFFLINE.message)
            api?.statusCode == CommonStatusCodes.CANCELED -> Outcome.Canceled
            api?.statusCode == CommonStatusCodes.DEVELOPER_ERROR ->
                Outcome.Failed("Google doesn't recognize this copy of Nuvori (Google code 10). Google Drive sync isn't set up for it. Sync over Wi-Fi works as usual.")
            else -> Outcome.Failed("Couldn't connect Google Drive (Google code ${api?.statusCode ?: "none"}). Try again.")
        }
    }
}

/** Tokens for background passes: no screen can be shown, so a needed consent is [CloudError.AUTH]. */
internal class GoogleDriveAccess(private val context: Context) : DriveAccess {
    private var token: String? = null

    override fun token(): String = token ?: when (val outcome = GoogleDriveSignIn.authorize(context)) {
        is GoogleDriveSignIn.Outcome.Token -> outcome.accessToken.also { token = it }
        is GoogleDriveSignIn.Outcome.Failed ->
            throw CloudException(if (outcome.message == CloudError.OFFLINE.message) CloudError.OFFLINE else CloudError.AUTH)
        else -> throw CloudException(CloudError.AUTH)
    }

    override fun refresh(stale: String) {
        GoogleDriveSignIn.clearToken(context, stale)
        token = null
    }
}
