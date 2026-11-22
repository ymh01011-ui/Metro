/*
 * Finds Apple Music "motion artwork" (the short looping album videos) for a song.
 *
 * Re-implementation of the lookup flow used by Metrolist (MetrolistGroup/Metrolist, GPL-3.0),
 * simplified for Metro: iTunes search -> album id -> Apple Music catalog API -> HLS video URL.
 * Keep the GPL-3.0 attribution if you publish this.
 *
 * NOTE: this uses Apple's web catalog API with the public token that Apple's own web player
 * ships in its JavaScript. It is unofficial and can stop working whenever Apple changes it.
 */
package code.name.monkey.retromusic.animatedcover

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AnimatedCoverResolver private constructor(context: Context) {

    /** [tallUrl] is the 3:4 portrait video (best for a full-screen cover), may be null. */
    data class Cover(val squareUrl: String, val tallUrl: String?)

    private class Hit(val cover: Cover?)

    private class Track(
        val name: String,
        val artist: String,
        val collectionId: Long,
        val collection: String?,
    )

    private sealed class Motion {
        class Found(val cover: Cover) : Motion()
        object Missing : Motion()
        object Failed : Motion()
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val tokenMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    init {
        prune()
    }

    /**
     * Returns the motion cover for the song, or null when there is none (or the lookup failed).
     * Definitive answers (found / not found) are cached on disk; failures are not.
     */
    suspend fun resolve(title: String, artist: String, album: String?): Cover? =
        withContext(Dispatchers.IO) {
            val t = title.trim()
            val a = artist.trim()
            if (t.isEmpty() || a.isEmpty() || isUnknown(a)) return@withContext null
            if (t.length > MAX_FIELD || a.length > MAX_FIELD) return@withContext null
            val alb = album?.trim()?.takeIf { it.isNotEmpty() && !isUnknown(it) && it.length <= MAX_FIELD }

            val key = cacheKey(t, a, alb)
            readCache(key)?.let { return@withContext it.cover }

            try {
                val country = storefront()
                val ids = findCollections(t, a, alb, country) ?: return@withContext null
                if (ids.isEmpty()) {
                    writeCache(key, null)
                    return@withContext null
                }
                val token = devToken(country) ?: return@withContext null
                var failed = false
                for (id in ids) {
                    when (val m = fetchMotion(id, token, country)) {
                        is Motion.Found -> {
                            // Found videos are stored by the caller in the shared album cache.
                            return@withContext m.cover
                        }
                        Motion.Failed -> failed = true
                        Motion.Missing -> Unit
                    }
                }
                if (!failed) writeCache(key, null)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "lookup failed: ${e.javaClass.simpleName}")
                null
            }
        }

    // ---------------------------------------------------------------- matching

    private suspend fun findCollections(
        title: String,
        artist: String,
        album: String?,
        country: String,
    ): List<Long>? {
        val wantedArtist = artistKey(artist)
        val wantedAlbum = album?.let { albumKey(it) }
        val titleAsAlbum = albumKey(title)
        var firstFailed = false

        if (wantedAlbum != null) {
            val results = search("$artist $album", country)
            if (results == null) {
                firstFailed = true
            } else {
                val ids = results
                    .filter { artistKey(it.artist) == wantedArtist }
                    .filter { it.collection != null && albumKey(it.collection) == wantedAlbum }
                    .map { it.collectionId }
                    .distinct()
                    .take(MAX_CANDIDATES)
                if (ids.isNotEmpty()) return ids
            }
        }

        val results = search("$artist $title", country) ?: return null
        val ids = results
            .filter { artistKey(it.artist) == wantedArtist }
            .filter { titleMatches(it.name, title) }
            .filter {
                val c = it.collection ?: return@filter false
                // Known album must match. Without an album only a self-titled release counts.
                if (wantedAlbum != null) albumKey(c) == wantedAlbum else albumKey(c) == titleAsAlbum
            }
            .map { it.collectionId }
            .distinct()
            .take(MAX_CANDIDATES)
        if (ids.isEmpty() && firstFailed) return null
        return ids
    }

    private suspend fun search(term: String, country: String): List<Track>? {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("itunes.apple.com")
            .addPathSegment("search")
            .addQueryParameter("term", term)
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "50")
            .addQueryParameter("country", country)
            .build()
        return try {
            val text = fetchText(Request.Builder().url(url).header("User-Agent", UA).build(), MAX_JSON)
                ?: return null
            val arr = JSONObject(text).optJSONArray("results") ?: return emptyList()
            val out = ArrayList<Track>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("kind") != "song") continue
                val id = o.optLong("collectionId", 0L)
                if (id == 0L) continue
                out.add(
                    Track(
                        name = o.optString("trackName"),
                        artist = o.optString("artistName"),
                        collectionId = id,
                        collection = o.optString("collectionName").ifEmpty { null },
                    ),
                )
            }
            out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun titleMatches(candidate: String, raw: String): Boolean {
        val a = normalize(candidate)
        val b = normalize(raw)
        if (a == b) return true
        // Versions (live, remix, slowed, Taylor's Version...) must match exactly.
        if (VARIANT.containsMatchIn(a) || VARIANT.containsMatchIn(b)) return false
        return FEATURING.replace(a, "").trim() == FEATURING.replace(b, "").trim()
    }

    private fun normalize(s: String): String =
        s.trim()
            .lowercase(Locale.ROOT)
            .replace('\u2019', '\'')
            .replace('\u2018', '\'')
            .replace('[', '(')
            .replace(']', ')')
            .replace(SPACES, " ")

    private fun artistKey(s: String): String = ARTIST_SPLIT.split(normalize(s)).first().trim()

    /** Album name without "(+ bonus)" notes, (Explicit)/(Clean) and "- Single"/"- EP". */
    private fun albumKey(s: String): String {
        var v = normalize(s)
        var prev: String
        do {
            prev = v
            v = TRAILING_NOTE.replace(v, "")
        } while (v != prev)
        v = EDITION.replace(v, "")
        v = RELEASE_SUFFIX.replace(v, "")
        return v.trim()
    }

    private fun isUnknown(s: String): Boolean {
        val v = s.trim().lowercase(Locale.ROOT)
        return v == "<unknown>" || v == "unknown" || v == "unknown artist"
    }

    // ---------------------------------------------------------------- Apple Music API

    private suspend fun fetchMotion(collectionId: Long, token: String, country: String): Motion {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("amp-api.music.apple.com")
            .addPathSegments("v1/catalog/$country/albums/$collectionId")
            .addQueryParameter("extend", "editorialArtwork,editorialVideo,extendedAssetUrls")
            .addQueryParameter("platform", "web")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Origin", "https://music.apple.com")
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .build()
        return try {
            client.newCall(request).await().use<Response, Motion> { r ->
                if (r.code == 401) {
                    prefs.edit().remove(KEY_TOKEN).remove(KEY_TOKEN_EXP).apply()
                    return@use Motion.Failed
                }
                if (!r.isSuccessful) return@use Motion.Failed
                val body = r.body?.readTextLimited(MAX_JSON) ?: return@use Motion.Failed
                val root = JSONObject(body)
                val attrs = root.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes")
                    ?: root.optJSONObject("resources")?.optJSONObject("albums")
                        ?.optJSONObject(collectionId.toString())?.optJSONObject("attributes")
                val ev = attrs?.optJSONObject("editorialVideo") ?: return@use Motion.Missing

                fun video(key: String): String? =
                    ev.optJSONObject(key)?.optString("video")?.takeIf { it.isNotBlank() && isTrusted(it) }

                val tall = listOf("motionTallVideo3x4", "motionDetailTall").firstNotNullOfOrNull { video(it) }
                val square = listOf("motionSquareVideo1x1", "motionDetailSquare").firstNotNullOfOrNull { video(it) }
                    ?: tall
                    ?: ev.keys().asSequence().firstNotNullOfOrNull { video(it) }
                if (square == null) Motion.Missing else Motion.Found(Cover(square, tall))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Motion.Failed
        }
    }

    /** The web player's public token: found by scanning the JS bundle of music.apple.com. */
    private suspend fun devToken(country: String): String? =
        tokenMutex.withLock {
            val now = System.currentTimeMillis() / 1000
            val saved = prefs.getString(KEY_TOKEN, null)
            if (saved != null && prefs.getLong(KEY_TOKEN_EXP, 0L) - now > 300) return@withLock saved
            try {
                val html = fetchText(browserRequest("https://music.apple.com/$country/browse"), MAX_HTML)
                    ?: return@withLock null
                val bundle = BUNDLE.find(html)?.value ?: return@withLock null
                val found = client.newCall(browserRequest("https://music.apple.com$bundle")).await().use { r ->
                    if (r.isSuccessful) r.body?.let { scanForToken(it, now) } else null
                } ?: return@withLock null
                prefs.edit().putString(KEY_TOKEN, found.first).putLong(KEY_TOKEN_EXP, found.second).apply()
                found.first
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "token fetch failed: ${e.javaClass.simpleName}")
                null
            }
        }

    /** Streams the script in chunks so a multi-MB bundle never sits in memory as one string. */
    private fun scanForToken(body: ResponseBody, nowSec: Long): Pair<String, Long>? {
        var best: Pair<String, Long>? = null

        fun consider(window: String, atEnd: Boolean) {
            for (m in JWT.findAll(window)) {
                // A match touching the end of a window may be cut off; the next window re-finds it.
                if (!atEnd && m.range.last == window.length - 1) continue
                val parts = m.value.split('.')
                if (parts.size != 3) continue
                val payload = try {
                    val raw = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                    JSONObject(String(raw, Charsets.UTF_8))
                } catch (e: Exception) {
                    continue
                }
                if (payload.optString("iss") != "AMPWebPlay") continue
                val exp = payload.optLong("exp", 0L)
                if (exp - nowSec <= 300) continue
                val current = best
                if (current == null || exp > current.second) best = Pair(m.value, exp)
            }
        }

        body.charStream().use { reader ->
            val buf = CharArray(64 * 1024)
            var carry = ""
            var total = 0L
            while (total < MAX_SCRIPT_CHARS) {
                val n = reader.read(buf)
                if (n < 0) break
                total += n
                val window = carry + String(buf, 0, n)
                consider(window, false)
                carry = window.takeLast(2048)
            }
            consider(carry, true)
        }
        return best
    }

    // ---------------------------------------------------------------- helpers

    private fun browserRequest(url: String): Request =
        Request.Builder().url(url).header("User-Agent", UA).header("Accept", "*/*").build()

    private suspend fun fetchText(request: Request, max: Int): String? =
        client.newCall(request).await().use { r ->
            if (r.isSuccessful) r.body?.readTextLimited(max) else null
        }

    private fun storefront(): String {
        val c = Locale.getDefault().country.lowercase(Locale.ROOT)
        return if (c.length == 2 && c.all { it in 'a'..'z' }) c else "us"
    }

    private fun isTrusted(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        if (u.scheme != "https") return false
        val h = u.host.lowercase(Locale.ROOT)
        return h == "itunes.apple.com" || h.endsWith(".apple.com") ||
            h.endsWith(".mzstatic.com") || h.endsWith(".akamaized.net")
    }

    // ---------------------------------------------------------------- disk cache

    private fun cacheKey(title: String, artist: String, album: String?): String {
        val raw = "${normalize(title)}|${normalize(artist)}|${album?.let { normalize(it) }.orEmpty()}|$CACHE_VERSION"
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun readCache(key: String): Hit? {
        val value = prefs.getString("c_$key", null) ?: return null
        val p = value.split('\t')
        if (p.size < 3) return null
        val age = System.currentTimeMillis() - (p[0].toLongOrNull() ?: return null)
        return if (p[1].isEmpty()) {
            if (age > MISSING_TTL_MS) null else Hit(null)
        } else {
            if (age > FOUND_TTL_MS) null else Hit(Cover(p[1], p[2].ifEmpty { null }))
        }
    }

    private fun writeCache(key: String, cover: Cover?) {
        val value = "${System.currentTimeMillis()}\t${cover?.squareUrl.orEmpty()}\t${cover?.tallUrl.orEmpty()}"
        prefs.edit().putString("c_$key", value).apply()
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        val live = ArrayList<Pair<String, Long>>()
        val edit = prefs.edit()
        for ((k, v) in prefs.all) {
            if (!k.startsWith("c_")) continue
            val ts = (v as? String)?.substringBefore('\t')?.toLongOrNull()
            if (ts == null || now - ts > FOUND_TTL_MS) edit.remove(k) else live.add(k to ts)
        }
        if (live.size > MAX_ENTRIES) {
            live.sortBy { it.second }
            live.take(live.size - MAX_ENTRIES).forEach { edit.remove(it.first) }
        }
        edit.apply()
    }

    companion object {
        private const val TAG = "AnimatedCover"
        private const val PREFS = "animated_cover_cache"
        private const val KEY_TOKEN = "token"
        private const val KEY_TOKEN_EXP = "token_exp"
        private const val CACHE_VERSION = "m1"
        private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"
        private const val MAX_FIELD = 4096
        private const val MAX_CANDIDATES = 8
        private const val MAX_ENTRIES = 1000
        private const val MAX_JSON = 4 * 1024 * 1024
        private const val MAX_HTML = 4 * 1024 * 1024
        private const val MAX_SCRIPT_CHARS = 16L * 1024 * 1024
        private const val FOUND_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val MISSING_TTL_MS = 3L * 24 * 60 * 60 * 1000

        private val SPACES = Regex("\\s+")
        private val BUNDLE = Regex("/assets/index[^\"'\\s]*\\.js")
        private val JWT = Regex("eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        private val TRAILING_NOTE = Regex("\\s*\\(\\s*\\+[^)]*\\)\\s*$")
        private val EDITION = Regex("\\s*\\(\\s*(explicit|clean)\\s*\\)\\s*$")
        private val RELEASE_SUFFIX = Regex("\\s+-\\s+(single|ep)$")
        private val FEATURING = Regex("\\s*\\(\\s*(feat\\.?|ft\\.?|with|prod\\.?)[^)]*\\)")
        private val VARIANT = Regex(
            "\\([^)]*(live|remix|acoustic|version|instrumental|slowed|sped|reverb|remaster|demo|edit|mix)[^)]*\\)",
        )
        private val ARTIST_SPLIT = Regex("\\s*(?:,|&|\\bfeat\\.?\\b|\\bft\\.?\\b|\\sx\\s|\\sand\\s)\\s*")

        @Volatile
        private var instance: AnimatedCoverResolver? = null

        fun get(context: Context): AnimatedCoverResolver =
            instance ?: synchronized(this) {
                instance ?: AnimatedCoverResolver(context.applicationContext).also { instance = it }
            }
    }
}

private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            },
        )
    }

private fun ResponseBody.readTextLimited(max: Int): String? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    byteStream().use { input ->
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > max) return null
            out.write(buf, 0, n)
        }
    }
    return out.toString("UTF-8")
}
