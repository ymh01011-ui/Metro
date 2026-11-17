package code.name.monkey.retromusic.util.color

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pulls a handful of *distinct* real colors out of album art using k-means,
 * the way Apple Music's now-playing background does (instead of Palette swatches,
 * which tend to return several near-identical shades of the same color).
 */
object CoverColorExtractor {

    private const val SAMPLE_SIZE = 56
    private const val CLUSTERS = 9
    private const val ITERATIONS = 12
    private const val MIN_DISTANCE = 58f // RGB distance between two picked colors

    /** Returns up to [maxColors] colors, most important first. Empty if nothing usable. */
    fun extract(source: Bitmap, maxColors: Int = 5): IntArray {
        val bmp = Bitmap.createScaledBitmap(source, SAMPLE_SIZE, SAMPLE_SIZE, true)
        val n = SAMPLE_SIZE * SAMPLE_SIZE
        val px = IntArray(n)
        bmp.getPixels(px, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)

        val r = FloatArray(n) { ((px[it] shr 16) and 0xFF).toFloat() }
        val g = FloatArray(n) { ((px[it] shr 8) and 0xFF).toFloat() }
        val b = FloatArray(n) { (px[it] and 0xFF).toFloat() }

        // k-means++ init (fixed seed => same cover always gives same colors)
        val rnd = Random(7)
        val cr = FloatArray(CLUSTERS)
        val cg = FloatArray(CLUSTERS)
        val cb = FloatArray(CLUSTERS)
        val first = rnd.nextInt(n)
        cr[0] = r[first]; cg[0] = g[first]; cb[0] = b[first]
        val dist = FloatArray(n)
        for (c in 1 until CLUSTERS) {
            var total = 0f
            for (i in 0 until n) {
                var best = Float.MAX_VALUE
                for (j in 0 until c) {
                    val d = sq(r[i] - cr[j]) + sq(g[i] - cg[j]) + sq(b[i] - cb[j])
                    if (d < best) best = d
                }
                dist[i] = best
                total += best
            }
            var pick = rnd.nextFloat() * total
            var idx = n - 1
            for (i in 0 until n) {
                pick -= dist[i]
                if (pick <= 0f) { idx = i; break }
            }
            cr[c] = r[idx]; cg[c] = g[idx]; cb[c] = b[idx]
        }

        val assign = IntArray(n)
        val count = IntArray(CLUSTERS)
        repeat(ITERATIONS) {
            for (i in 0 until n) {
                var best = Float.MAX_VALUE
                var bi = 0
                for (j in 0 until CLUSTERS) {
                    val d = sq(r[i] - cr[j]) + sq(g[i] - cg[j]) + sq(b[i] - cb[j])
                    if (d < best) { best = d; bi = j }
                }
                assign[i] = bi
            }
            val sr = FloatArray(CLUSTERS)
            val sg = FloatArray(CLUSTERS)
            val sb = FloatArray(CLUSTERS)
            count.fill(0)
            for (i in 0 until n) {
                val a = assign[i]
                sr[a] += r[i]; sg[a] += g[i]; sb[a] += b[i]; count[a]++
            }
            for (j in 0 until CLUSTERS) {
                if (count[j] > 0) {
                    cr[j] = sr[j] / count[j]; cg[j] = sg[j] / count[j]; cb[j] = sb[j] / count[j]
                }
            }
        }

        // Score clusters: big + colorful wins; pure white / black / gray backgrounds are skipped
        class Cand(val rgb: Int, val score: Float)
        val hsl = FloatArray(3)
        val cands = ArrayList<Cand>()
        for (j in 0 until CLUSTERS) {
            if (count[j] == 0) continue
            val rgb = 0xFF000000.toInt() or
                (cr[j].toInt().coerceIn(0, 255) shl 16) or
                (cg[j].toInt().coerceIn(0, 255) shl 8) or
                cb[j].toInt().coerceIn(0, 255)
            ColorUtils.colorToHSL(rgb, hsl)
            val l = hsl[2]
            val s = hsl[1]
            if (l > 0.93f || l < 0.05f) continue
            if (s < 0.10f && (l > 0.85f || l < 0.12f)) continue
            val share = count[j] / n.toFloat()
            cands += Cand(rgb, share * (0.35f + s))
        }
        cands.sortByDescending { it.score }

        val picked = ArrayList<Int>()
        for (c in cands) {
            if (picked.size >= maxColors) break
            if (picked.all { colorDistance(it, c.rgb) > MIN_DISTANCE }) picked += c.rgb
        }
        return picked.toIntArray()
    }

    private fun sq(v: Float) = v * v

    private fun colorDistance(a: Int, b: Int): Float {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }
}
