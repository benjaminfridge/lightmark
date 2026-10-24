package com.benfridge.lightmark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.benfridge.lightmark.markdown.Block
import com.benfridge.lightmark.markdown.Document
import com.benfridge.lightmark.markdown.Emphasis
import com.benfridge.lightmark.reader.MeasuredBlock
import com.benfridge.lightmark.reader.PlacedSlice
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightTypography
import com.thelightphone.sdk.ui.designVerticalPxToSp

/**
 * Reader typography and page rhythm.
 *
 * Vertical values are grid units (the LP3 grid is 27 x 31), so the page keeps the same
 * proportions the rest of LightOS uses. Body text is `paragraphWide` rather than
 * `paragraph`: 1.30 leading and 0.02em tracking against 1.25 and none, which is the
 * difference between a UI label and something you read for ten minutes.
 */
internal object ReaderMetrics {
    const val SIDE_MARGIN_UNITS = 1.75f
    const val TOP_MARGIN_UNITS = 1.25f
    const val BOTTOM_MARGIN_UNITS = 1.25f

    const val LIST_MARKER_UNITS = 1.9f
    const val QUOTE_INSET_UNITS = 1.2f
    const val CODE_INSET_UNITS = 0.8f

    /** Height a thematic break occupies, hairline included. */
    const val RULE_UNITS = 1.2f
}

internal fun blockSpacingUnits(block: Block): Float = when (block) {
    is Block.Heading -> if (block.level <= 1) 1.3f else 1.05f
    is Block.Paragraph -> 0.7f
    is Block.Quote -> 0.85f
    is Block.Code -> 0.85f
    is Block.ListItem -> 0.3f
    is Block.Rule -> 0.9f
}

internal fun blockInsetUnits(block: Block): Float = when (block) {
    is Block.ListItem -> ReaderMetrics.LIST_MARKER_UNITS
    is Block.Quote -> ReaderMetrics.QUOTE_INSET_UNITS
    is Block.Code -> ReaderMetrics.CODE_INSET_UNITS
    else -> 0f
}

/**
 * The type scale LightOS applies to every other surface.
 *
 * `LightThemeTokens.typography` holds unscaled *design* values; `LightText` scales them
 * by screen height before rendering, in its own `variantStyle`. This renderer cannot use
 * `LightText` (it needs `AnnotatedString` and `onTextLayout`), so it has to apply the
 * same scale itself. Skipping it renders the reader about 27% larger than the rest of
 * the system, which is both wrong and the kind of thing a design review notices.
 *
 * The scale is derived through the SDK's own public helper rather than reading
 * `LocalConfiguration` directly, so it cannot drift from what `LightText` does.
 */
@Composable
internal fun rememberFontScale(): Float = 1f.designVerticalPxToSp().value

private fun TextUnit.scaled(factor: Float): TextUnit =
    if (this == TextUnit.Unspecified) this else (value * factor).sp

private fun TextStyle.scaled(factor: Float): TextStyle = copy(
    fontSize = fontSize.scaled(factor),
    lineHeight = lineHeight.scaled(factor),
    letterSpacing = letterSpacing.scaled(factor),
)

/**
 * [fontScale] comes from [rememberFontScale].
 *
 * Headings deliberately start at `subtitle`, not `title`: `title` is 115sp of design
 * scale, which is a fifth of the screen's height for a single line. It is the clock
 * face, not a chapter opening.
 */
internal fun blockStyle(
    block: Block,
    typography: LightTypography,
    fontScale: Float,
): TextStyle {
    val base = when (block) {
        is Block.Heading -> when (block.level) {
            1 -> typography.subtitle
            2 -> typography.heading
            3 -> typography.subheading
            else -> typography.copy
        }
        is Block.Paragraph -> typography.paragraphWide
        is Block.Quote -> typography.paragraphWide.copy(fontStyle = FontStyle.Italic)
        is Block.ListItem -> typography.paragraphWide
        is Block.Code -> typography.detail.copy(fontFamily = FontFamily.Monospace)
        is Block.Rule -> typography.paragraphWide
    }
    return base.scaled(fontScale)
}

/** Emphasis only. Highlights are layered on at render time and change no metrics. */
internal fun blockAnnotated(block: Block): AnnotatedString = buildAnnotatedString {
    block.spans.forEach { span ->
        pushStyle(span.emphasis.toSpanStyle())
        append(span.text)
        pop()
    }
}

private fun Set<Emphasis>.toSpanStyle(): SpanStyle = SpanStyle(
    fontWeight = if (Emphasis.Bold in this) FontWeight.Bold else null,
    fontStyle = if (Emphasis.Italic in this) FontStyle.Italic else null,
    fontFamily = if (Emphasis.Code in this) FontFamily.Monospace else null,
    textDecoration = if (Emphasis.Link in this) TextDecoration.Underline else null,
)

/**
 * Measures every block once at the page width, producing the line geometry
 * [com.benfridge.lightmark.reader.paginate] needs.
 *
 * Highlights are deliberately not part of this: they only set background and colour, so
 * they cannot move a line, and measuring without them means a new highlight never
 * triggers a re-measure or a re-paginate.
 */
@Composable
internal fun rememberMeasuredBlocks(
    document: Document,
    contentWidthPx: Int,
    unitPx: Float,
    typography: LightTypography,
    fontScale: Float,
    measurer: TextMeasurer,
): List<MeasuredBlock> = remember(document, contentWidthPx, unitPx, typography, fontScale) {
    if (contentWidthPx <= 0 || unitPx <= 0f) return@remember emptyList()

    document.blocks.map { placed ->
        val block = placed.block
        val spacing = blockSpacingUnits(block) * unitPx

        if (block is Block.Rule) {
            val h = ReaderMetrics.RULE_UNITS * unitPx
            return@map MeasuredBlock(
                lineTops = listOf(0f),
                lineBottoms = listOf(h),
                charStarts = listOf(0),
                charEnds = listOf(0),
                spacingBefore = spacing,
                keepWhole = true,
            )
        }

        val insetPx = (blockInsetUnits(block) * unitPx).toInt()
        val text = blockAnnotated(block)
        if (text.isEmpty()) {
            return@map MeasuredBlock(emptyList(), emptyList(), emptyList(), emptyList(), spacing)
        }

        val layout = measurer.measure(
            text = text,
            style = blockStyle(block, typography, fontScale),
            constraints = Constraints(maxWidth = (contentWidthPx - insetPx).coerceAtLeast(1)),
        )

        val lines = layout.lineCount
        MeasuredBlock(
            lineTops = List(lines) { layout.getLineTop(it) },
            lineBottoms = List(lines) { layout.getLineBottom(it) },
            charStarts = List(lines) { layout.getLineStart(it) },
            charEnds = List(lines) { layout.getLineEnd(it, visibleEnd = false) },
            spacingBefore = spacing,
            keepWhole = block is Block.Heading,
        )
    }
}

/**
 * Draws one page.
 *
 * Slices are positioned absolutely from [layoutPage]'s arithmetic rather than stacked in
 * a Column, so what is drawn sits exactly where the measuring pass said it would. The
 * hit-tester relies on that, and a highlight landing one line off is worse than none.
 */
@Composable
internal fun PageContent(
    document: Document,
    placed: List<PlacedSlice>,
    highlights: List<DocumentRange>,
    selection: DocumentRange?,
    colors: LightColors,
    typography: LightTypography,
    fontScale: Float,
    unitPx: Float,
    density: Density,
    onSliceLayout: (Int, TextLayoutResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        placed.forEachIndexed { index, placedSlice ->
            val slice = placedSlice.slice
            val documentBlock = document.blocks[slice.blockIndex]
            val block = documentBlock.block
            val heightDp = with(density) { placedSlice.height.toDp() }
            val topDp = with(density) { placedSlice.top.toDp() }
            val insetDp = with(density) { (blockInsetUnits(block) * unitPx).toDp() }

            Box(
                modifier = Modifier
                    .offset(y = topDp)
                    .fillMaxWidth()
                    .height(heightDp),
            ) {
                val full = if (block is Block.Rule) AnnotatedString("") else blockAnnotated(block)
                val hasText = block !is Block.Rule &&
                    full.isNotEmpty() &&
                    slice.charEnd > slice.charStart

                if (block is Block.Rule) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.contentSecondary),
                    )
                }

                if (block is Block.ListItem && slice.isBlockStart) {
                    Text(
                        text = block.marker,
                        style = blockStyle(block, typography, fontScale),
                        color = colors.contentSecondary,
                    )
                }

                if (hasText) Text(
                    text = styledSlice(
                        full = full,
                        sliceDocumentStart = documentBlock.start + slice.charStart,
                        charStart = slice.charStart,
                        charEnd = slice.charEnd.coerceAtMost(full.length),
                        highlights = highlights,
                        selection = selection,
                        colors = colors,
                    ),
                    style = blockStyle(block, typography, fontScale),
                    color = if (block is Block.Quote) colors.contentSecondary else colors.content,
                    maxLines = slice.lastLine - slice.firstLine + 1,
                    onTextLayout = { onSliceLayout(index, it) },
                    modifier = Modifier.fillMaxWidth().padding(start = insetDp),
                )
            }
        }
    }
}

/**
 * The slice's text with highlights and the in-progress selection layered over the top.
 *
 * A highlight inverts: LightOS gives a tool a background, a foreground and a dimmer
 * foreground, so there is no colour to wash text with. Inversion is what a highlighter
 * means on a monochrome screen, and it survives a theme switch unchanged. The live
 * selection underlines instead, so you can see what you are about to commit without it
 * looking like it already happened.
 */
private fun styledSlice(
    full: AnnotatedString,
    sliceDocumentStart: Int,
    charStart: Int,
    charEnd: Int,
    highlights: List<DocumentRange>,
    selection: DocumentRange?,
    colors: LightColors,
): AnnotatedString = buildAnnotatedString {
    append(full.subSequence(charStart, charEnd))

    highlights.forEach { range ->
        val from = (range.start - sliceDocumentStart).coerceIn(0, length)
        val to = (range.end - sliceDocumentStart).coerceIn(0, length)
        if (to > from) {
            addStyle(SpanStyle(background = colors.content, color = colors.background), from, to)
        }
    }

    selection?.let { range ->
        val from = (range.start - sliceDocumentStart).coerceIn(0, length)
        val to = (range.end - sliceDocumentStart).coerceIn(0, length)
        if (to > from) {
            addStyle(SpanStyle(textDecoration = TextDecoration.Underline), from, to)
        }
    }
}
