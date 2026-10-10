package code.name.monkey.retromusic.network

import android.content.Context
import code.name.monkey.retromusic.util.MusicUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * بيجيب نبذة (Biography) عن الفنان من Last.fm API، بنفس أسلوب
 * ArtistImageFetcher.kt بالظبط (اتصال HTTP مباشر + JSONObject من غير
 * Retrofit) عشان يفضل متسق مع باقي الكود.
 *
 * النص بيتحفظ في SharedPreferences بعد أول تحميل، فبيبقى متاح Offline
 * بعد كده بنفس فكرة الكاش بتاع الصور (Glide disk cache).
 *
 * Last.fm - على عكس Deezer - محتاج API key. تقدر تجيب واحد ببلاش من:
 * https://www.last.fm/api/account/create
 */
object LastFmBiographyFetcher {

    // ملحوظة: الـ API key ده مكشوف دلوقتي في الكود مباشرة بناءً على طلبك -
    // لما تكون جاهز تخبيه (BuildConfig / local.properties) قولي وأظبطها.
    private const val API_KEY = "3d5376f1ce3b00fa7507d3616c479fa1"
    private const val PREFS_NAME = "lastfm_biography_cache"
    private const val ALBUM_PREFS_NAME = "lastfm_album_about_cache"

    // لو Last.fm مالهاش نبذة للألبوم، منسألش تاني قبل المدة دي (عشان منعملش طلبات على الفاضي كل فتحة)
    private const val NOT_FOUND_TTL_MS = 7L * 24 * 60 * 60 * 1000

    // نتيجة طلب واحد: text = النبذة لو لقيناها، error = فشل مؤقت (نت/سيرفر) مش "مفيش نبذة"
    private class AlbumResult(val text: String?, val error: Boolean)

    suspend fun fetchBiography(context: Context, artistName: String): String? =
        withContext(Dispatchers.IO) {
            if (MusicUtil.isArtistNameUnknown(artistName) || API_KEY.isBlank()) {
                return@withContext null
            }

            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cacheKey = artistName.trim().lowercase()

            // بنجرب الكاش الأول عشان النبذة تشتغل Offline بعد أول تحميل
            prefs.getString(cacheKey, null)?.let { cached ->
                return@withContext cached
            }

            try {
                val query = URLEncoder.encode(artistName, "UTF-8")
                val url = URL(
                    "https://ws.audioscrobbler.com/2.0/?method=artist.getinfo" +
                            "&artist=$query&api_key=$API_KEY&format=json&autocorrect=1"
                )

                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                }

                val json = try {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }

                val artistObject = JSONObject(json).optJSONObject("artist")
                    ?: return@withContext null
                val bioObject = artistObject.optJSONObject("bio") ?: return@withContext null
                val summary = bioObject.optString("summary")

                val bio = cleanBiography(summary)
                if (bio.isNotEmpty()) {
                    prefs.edit().putString(cacheKey, bio).apply()
                    bio
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * بيجيب نبذة (About) عن الألبوم من Last.fm (album.getinfo) بنفس أسلوب نبذة الفنان بالظبط:
     * نفس الـ API key، ونفس تنضيف الـ HTML، ونفس الكاش في SharedPreferences (بيشتغل Offline بعد أول تحميل).
     *
     * [artistCandidates]: أسماء الفنان بالترتيب اللي نجرّبه (Album artist الأول). بنجرب كل اسم مع عنوان
     * الألبوم زي ما هو، وبعدين من غير الأقواس و"- Single" (زي "(Deluxe Edition)")، لأن Last.fm ساعات
     * بتسجل الألبوم باسمه الأساسي. بنقف عند أول نبذة نلاقيها.
     */
    suspend fun fetchAlbumAbout(
        context: Context,
        artistCandidates: List<String>,
        albumTitle: String
    ): String? = withContext(Dispatchers.IO) {
        val artists = artistCandidates.map { it.trim() }
            .filter { it.isNotEmpty() && !MusicUtil.isArtistNameUnknown(it) }
            .distinct()
        val title = albumTitle.trim()
        if (artists.isEmpty() || title.isEmpty() || API_KEY.isBlank()) return@withContext null

        val prefs = context.applicationContext
            .getSharedPreferences(ALBUM_PREFS_NAME, Context.MODE_PRIVATE)
        val cacheKey = "album::${artists.first().lowercase()}::${title.lowercase()}"
        val notFoundKey = "none::$cacheKey"

        prefs.getString(cacheKey, null)?.let { return@withContext it }
        val notFoundAt = prefs.getLong(notFoundKey, 0L)
        if (notFoundAt > 0 && System.currentTimeMillis() - notFoundAt < NOT_FOUND_TTL_MS) {
            return@withContext null
        }

        val titles = listOf(title, baseAlbumTitle(title)).filter { it.isNotEmpty() }.distinct()
        var hadError = false
        for (artist in artists) {
            for (candidateTitle in titles) {
                val result = lookupAlbum(artist, candidateTitle)
                if (result.text != null) {
                    prefs.edit().putString(cacheKey, result.text).remove(notFoundKey).apply()
                    return@withContext result.text
                }
                if (result.error) hadError = true
            }
        }
        // مفيش نبذة فعلًا (مش فشل نت): نفتكر ده فترة
        if (!hadError) prefs.edit().putLong(notFoundKey, System.currentTimeMillis()).apply()
        null
    }

    private fun lookupAlbum(artist: String, album: String): AlbumResult {
        return try {
            val url = URL(
                "https://ws.audioscrobbler.com/2.0/?method=album.getinfo" +
                        "&artist=${URLEncoder.encode(artist, "UTF-8")}" +
                        "&album=${URLEncoder.encode(album, "UTF-8")}" +
                        "&api_key=$API_KEY&format=json&autocorrect=1"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                if (code >= 500 || code == 429 || stream == null) return AlbumResult(null, true)
                val obj = JSONObject(stream.bufferedReader().use { it.readText() })
                if (obj.has("error")) {
                    // 6 = الألبوم مش موجود. أي كود تاني (سيرفر واقع، ضغط طلبات...) فشل مؤقت
                    return AlbumResult(null, obj.optInt("error") != 6)
                }
                val wiki = obj.optJSONObject("album")?.optJSONObject("wiki")
                    ?: return AlbumResult(null, false)
                val text = cleanBiography(wiki.optString("summary"))
                AlbumResult(text.ifEmpty { null }, false)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            AlbumResult(null, true)
        }
    }

    // "Dreamland (+ Bonus Levels)" -> "Dreamland"، "Foo - Single" -> "Foo"، "Bar - Deluxe Edition" -> "Bar"
    private fun baseAlbumTitle(title: String): String =
        title.replace(Regex("""\s*[\(\[][^)\]]*[)\]]"""), "")
            .replace(Regex("""\s+-\s+(single|ep|.*(edition|version|remaster(ed)?).*)\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()

    /**
     * Last.fm بيرجع النص بـ HTML tags، وبيضيف في الآخر لينك
     * "<a href=...>Read more on Last.fm</a>" - بنشيلهم عشان يبان
     * كنص عادي نضيف من غير HTML أو لينكات.
     */
    private fun cleanBiography(rawSummary: String): String {
        return rawSummary
            .replace(Regex("<a[^>]*>.*?</a>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]+>"), "")
            .trim()
    }
}
