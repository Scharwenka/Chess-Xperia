package com.scharwenka.xperiamix.align

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * FFT complexe iterative (Cooley-Tukey, radix 2), en place, en double precision.
 *
 * Les facteurs de rotation sont lus dans une table complete plutot que calcules
 * par recurrence : sur une transformee de 2^20 points la recurrence accumule
 * une erreur de phase qui elargit le pic de correlation - exactement ce que le
 * calage cherche a affiner.
 */
object Fft {

    private var cachedSize = 0
    private var cosTable = DoubleArray(0)
    private var sinTable = DoubleArray(0)

    fun nextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    @Synchronized
    private fun tables(n: Int) {
        if (cachedSize == n) return
        cosTable = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
        sinTable = DoubleArray(n / 2) { sin(2.0 * PI * it / n) }
        cachedSize = n
    }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n == im.size) { "re et im doivent avoir la meme taille" }
        require(n > 1 && (n and (n - 1)) == 0) { "la taille doit etre une puissance de deux" }
        tables(n)

        // Permutation par inversion de bits.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val stride = n / len
            var base = 0
            while (base < n) {
                for (k in 0 until half) {
                    val tw = k * stride
                    val wRe = cosTable[tw]
                    val wIm = sign * sinTable[tw]
                    val i0 = base + k
                    val i1 = i0 + half
                    val vRe = re[i1] * wRe - im[i1] * wIm
                    val vIm = re[i1] * wIm + im[i1] * wRe
                    re[i1] = re[i0] - vRe
                    im[i1] = im[i0] - vIm
                    re[i0] += vRe
                    im[i0] += vIm
                }
                base += len
            }
            len = len shl 1
        }

        if (inverse) {
            val scale = 1.0 / n
            for (i in 0 until n) {
                re[i] *= scale
                im[i] *= scale
            }
        }
    }
}
