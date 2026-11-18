package code.name.monkey.retromusic.util.color

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Extracts 5 background colors from album art, Apple Music style:
 *  1. k-means finds the real color groups of the cover.
 *  2. Saturated groups win over gray/white/beige ones.
 *  3. Colors must differ from each other (hue or lightness), so the mesh has contrast.
 *  4. If the cover has fewer than 5 distinct colors (e.g. all brown), the missing ones are
 *     shades of the main hue, so there is still visible movement.
 *  5. Each color keeps its hue, and is brought to a deep, rich range (never gray, never pale).
 */
object CoverColorExtractor {

    private const val SIZE = 48
    private const val K = 8
    private const val ITERATIONS = 12

    private class Hsl(val h: Float, val s: Float, val l: Float)

    fun extract(source: Bitmap, want: Int = 5): IntArray {
        val bmp = Bitmap.createScaledBitmap(source, SIZE, SIZE, true)
        val n = SIZE * SIZE
        val px = IntArray(n)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val r = FloatArray(n) { ((px[it] shr 16) and 0xFF).toFloat() }
        val g = FloatArray(n) { ((px[it] shr 8) and 0xFF).toFloat() }
        val b = FloatArray(n) { (px[it] and 0xFF).toFloat() }

        // k-means++ init, fixed seed so the same cover always gives the same colors
        val rnd = Random(7)
        val cr = FloatArray(K); val cg = FloatArray(K); val cb = FloatArray(K)
        val first = rnd.nextInt(n)
        cr[0] = r[first]; cg[0] = g[first]; cb[0] = b[first]
        val dist = FloatArray(n)
        for (c in 1 until K) {
            var total = 0f
            for (i in 0 until n) {
                var best = Float.MAX_VALUE
                for (j in 0 until c) {
                    val d = sq(r[i] - cr[j]) + sq(g[i] - cg[j]) + sq(b[i] - cb[j])
                    if (d < best) best = d
                }
                dist[i] = best; total += best
            }
            var pick = rnd.nextFloat() * total
            var idx = n - 1
            for (i in 0 until n) { pick -= dist[i]; if (pick <= 0f) { idx = i; break } }
            cr[c] = r[idx]; cg[c] = g[idx]; cb[c] = b[idx]
        }

        val assign = IntArray(n)
        val count = IntArray(K)
        repeat(ITERATIONS) {
            for (i in 0 until n) {
                var best = Float.MAX_VALUE; var bi = 0
                for (j in 0 until K) {
                    val d = sq(r[i] - cr[j]) + sq(g[i] - cg[j]) + sq(b[i] - cb[j])
                    if (d < best) { best = d; bi = j }
                }
                assign[i] = bi
            }
            val sr = FloatArray(K); val sg = FloatArray(K); val sb = FloatArray(K)
            count.fill(0)
            for (i in 0 until n) { val a = assign[i]; sr[a] += r[i]; sg[a] += g[i]; sb[a] += b[i]; count[a]++ }
            for (j in 0 until K) if (count[j] > 0) {
                cr[j] = sr[j] / count[j]; cg[j] = sg[j] / count[j]; cb[j] = sb[j] / count[j]
            }
        }

        // Score: size, with saturation weighted strongly
        class Cand(val hsl: Hsl, val score: Float)
        val tmp = FloatArray(3)
        val cands = ArrayList<Cand>()
        for (j in 0 until K) {
            if (count[j] == 0) continue
            val rgb = (0xFF shl 24) or
                (cr[j].toInt().coerceIn(0, 255) shl 16) or
                (cg[j].toInt().coerceIn(0, 255) shl 8) or
                cb[j].toInt().coerceIn(0, 255)
            ColorUtils.colorToHSL(rgb, tmp)
            val h = tmp[0]; val s = tmp[1]; val l = tmp[2]
            if (l > 0.93f || l < 0.05f) continue
            val share = count[j] / n.toFloat()
            cands += Cand(Hsl(h, s, l), share * (0.15f + s.pow(1.5f) * 2f))
        }
        cands.sortByDescending { it.score }

        val picked = ArrayList<Hsl>()
        for (c in cands) {
            val x = c.hsl
            if (x.s < 0.15f && picked.isNotEmpty()) continue // no gray once a real hue exists
            val distinct = picked.all {
                hueDiff(it.h, x.h) > 22f || abs(it.l - x.l) > 0.18f
            }
            if (distinct) picked += x
            if (picked.size == want) break
        }
        if (picked.isEmpty()) picked += Hsl(30f, 0.2f, 0.4f)

        // Fewer than `want` distinct colors -> shades of the main hue
        val m = picked[0]
        val shades = listOf(
            Hsl(m.h + 8f, m.s, m.l * 0.7f),
            Hsl(m.h - 8f, m.s, min(0.8f, m.l * 1.25f)),
            Hsl(m.h + 14f, m.s * 0.9f, m.l * 0.85f)
        )
        var i = 0
        while (picked.size < want) picked += shades[i++ % shades.size]

        return IntArray(want) { toRich(picked[it]) }
    }

    // Keep the hue; make it deep and never gray (what Apple's background looks like)
    private fun toRich(c: Hsl): Int {
        val hsl = floatArrayOf(
            ((c.h % 360f) + 360f) % 360f,
            c.s.coerceIn(0.30f, 0.85f),
            c.l.coerceIn(0.26f, 0.42f)
        )
        return ColorUtils.HSLToColor(hsl)
    }

    private fun hueDiff(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    private fun sq(v: Float) = v * v
}
