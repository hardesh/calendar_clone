package app.calendar

import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import java.io.File
import kotlin.test.Test

/**
 * Renders design/icon.svg to PNGs in a macOS .iconset layout under
 * shared/build/icon/Calendar.iconset (used by `just icons`). Uses Skia, which
 * Compose already ships, so no extra image tools are needed.
 */
class IconExportTest {
    @Test
    fun exportIcon() {
        val svg = File("../design/icon.svg")
        if (!svg.exists()) return
        val out = File("build/icon/Calendar.iconset").apply { mkdirs() }
        val sizes = listOf(16, 32, 64, 128, 256, 512, 1024)
        val pngs = sizes.associateWith { render(svg.readBytes(), it) }
        for (base in listOf(16, 32, 128, 256, 512)) {
            File(out, "icon_${base}x$base.png").writeBytes(pngs.getValue(base))
            File(out, "icon_${base}x${base}@2x.png").writeBytes(pngs.getValue(base * 2))
        }
        File("build/icon/icon-512.png").writeBytes(pngs.getValue(512))
        File("build/icon/icon-1024.png").writeBytes(pngs.getValue(1024))
    }

    private fun render(bytes: ByteArray, size: Int): ByteArray {
        val dom = SVGDOM(Data.makeFromBytes(bytes))
        dom.setContainerSize(1024f, 1024f)
        val surface = Surface.makeRasterN32Premul(size, size)
        surface.canvas.scale(size / 1024f, size / 1024f)
        dom.render(surface.canvas)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
}
