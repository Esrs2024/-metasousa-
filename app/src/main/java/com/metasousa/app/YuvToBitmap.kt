package com.metasousa.app

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Convierte un fotograma YUV de las gafas a Bitmap.
 * El kit de Meta entrega I420 (Y plano, U plano, V plano, sin huecos)
 * con coeficientes BT.709 de rango limitado.
 */
object YuvToBitmap {

    fun convert(buffer: ByteBuffer, width: Int, height: Int): Bitmap {
        val size = width * height
        val yuv = ByteArray(buffer.remaining())
        buffer.duplicate().get(yuv)

        val pixels = IntArray(size)
        val uvWidth = width / 2
        var p = 0

        for (y in 0 until height) {
            val yRow = y * width
            val uvRow = (y / 2) * uvWidth
            for (x in 0 until width) {
                val yVal = yuv[yRow + x].toInt() and 0xFF
                val uvIndex = uvRow + (x / 2)
                val uVal = yuv[size + uvIndex].toInt() and 0xFF
                val vVal = yuv[size + size / 4 + uvIndex].toInt() and 0xFF

                val c = yVal - 16
                val d = uVal - 128
                val e = vVal - 128

                var r = (298 * c + 459 * e + 128) shr 8
                var g = (298 * c - 55 * d - 136 * e + 128) shr 8
                var b = (298 * c + 541 * d + 128) shr 8

                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                pixels[p++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
