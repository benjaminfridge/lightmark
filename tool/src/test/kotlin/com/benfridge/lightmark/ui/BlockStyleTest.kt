package com.benfridge.lightmark.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.benfridge.lightmark.markdown.Block
import com.benfridge.lightmark.markdown.Span
import com.thelightphone.sdk.ui.LightTypography
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reader cannot use `LightText`, so it has to reproduce the screen-height scaling
 * `LightText` applies. Getting that wrong rendered the whole reader ~27% larger than
 * every other LightOS surface, which is invisible until you compare screens.
 */
class BlockStyleTest {

    private fun style(size: Float, line: Float = 0f, spacing: Float = 0f) = TextStyle(
        fontSize = size.sp,
        lineHeight = if (line > 0f) line.sp else TextUnit.Unspecified,
        letterSpacing = if (spacing > 0f) spacing.sp else TextUnit.Unspecified,
    )

    private val typography = LightTypography(
        title = style(115f),
        subtitle = style(52f),
        heading = style(38f),
        subheading = style(30f),
        copy = style(30f),
        button = style(30f),
        paragraph = style(24.5f),
        paragraphWide = style(25f, line = 32.5f, spacing = 0.5f),
        detail = style(20f),
        fine = style(16f),
        superfine = style(13f),
        micro = style(10f),
    )

    private fun para() = Block.Paragraph(listOf(Span("x")))

    @Test
    fun `body text is scaled by the screen-height factor`() {
        val scaled = blockStyle(para(), typography, fontScale = 0.787f)
        assertEquals(25f * 0.787f, scaled.fontSize.value, 0.001f)
    }

    @Test
    fun `line height and letter spacing scale with the font`() {
        val scaled = blockStyle(para(), typography, fontScale = 0.5f)
        assertEquals(32.5f * 0.5f, scaled.lineHeight.value, 0.001f)
        assertEquals(0.5f * 0.5f, scaled.letterSpacing.value, 0.001f)
    }

    @Test
    fun `a scale of one leaves the design values alone`() {
        val scaled = blockStyle(para(), typography, fontScale = 1f)
        assertEquals(25f, scaled.fontSize.value, 0.001f)
    }

    @Test
    fun `unspecified metrics stay unspecified rather than becoming zero`() {
        val scaled = blockStyle(
            Block.Heading(2, listOf(Span("x"))),
            typography,
            fontScale = 0.787f,
        )
        assertTrue(scaled.lineHeight == TextUnit.Unspecified, "lineHeight")
        assertTrue(scaled.letterSpacing == TextUnit.Unspecified, "letterSpacing")
    }

    // ---- heading mapping ----

    @Test
    fun `a top-level heading is not the clock face`() {
        val h1 = blockStyle(Block.Heading(1, listOf(Span("x"))), typography, 1f)
        assertEquals(52f, h1.fontSize.value, 0.001f, "h1 should be subtitle, not title")
        assertTrue(h1.fontSize.value < typography.title.fontSize.value)
    }

    @Test
    fun `heading levels step down in size`() {
        val sizes = (1..4).map {
            blockStyle(Block.Heading(it, listOf(Span("x"))), typography, 1f).fontSize.value
        }
        assertEquals(sizes, sizes.sortedDescending(), "each level should be no larger")
    }

    @Test
    fun `a heading is still larger than body text`() {
        val h1 = blockStyle(Block.Heading(1, listOf(Span("x"))), typography, 1f)
        val body = blockStyle(para(), typography, 1f)
        assertTrue(h1.fontSize.value > body.fontSize.value)
    }

    // ---- justification ----

    @Test
    fun `running text is justified and hyphenated`() {
        listOf(
            para(),
            Block.Quote(listOf(Span("x"))),
            Block.ListItem("\u2022", listOf(Span("x"))),
        ).forEach { block ->
            val style = blockStyle(block, typography, 1f, justify = true)
            assertEquals(TextAlign.Justify, style.textAlign, "$block alignment")
            assertEquals(Hyphens.Auto, style.hyphens, "$block hyphens")
            assertEquals(LineBreak.Paragraph, style.lineBreak, "$block line break")
        }
    }

    @Test
    fun `headings and code are not justified`() {
        listOf(
            Block.Heading(1, listOf(Span("x"))),
            Block.Heading(3, listOf(Span("x"))),
            Block.Code("x"),
        ).forEach { block ->
            val style = blockStyle(block, typography, 1f, justify = true)
            assertTrue(style.textAlign != TextAlign.Justify, "$block should stay ragged")
            assertTrue(style.hyphens != Hyphens.Auto, "$block should not hyphenate")
        }
    }

    @Test
    fun `prose is ragged unless justification is asked for`() {
        val style = blockStyle(para(), typography, 1f)
        assertTrue(style.textAlign != TextAlign.Justify, "ragged is the default")
        assertTrue(style.hyphens != Hyphens.Auto)
    }

    @Test
    fun `justification does not disturb the size or the scaling`() {
        val scaled = blockStyle(para(), typography, fontScale = 0.787f)
        assertEquals(25f * 0.787f, scaled.fontSize.value, 0.001f)
    }

    @Test
    fun `code is monospaced and quotes are italic`() {
        val code = blockStyle(Block.Code("x"), typography, 1f)
        val quote = blockStyle(Block.Quote(listOf(Span("x"))), typography, 1f)
        assertTrue(code.fontFamily != null)
        assertTrue(quote.fontStyle != null)
    }
}
