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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.yield
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
    return base.scaled(fontScale).let { if (block.isProse) it.justified() else it }
}

/** Running text. Headings, code and rules are set ragged-right as usual. */
private val Block.isProse: Boolean
    get() = this is Block.Paragraph || this is Block.Quote || this is Block.ListItem

/**
 * Justified, with automatic hyphenation.
 *
 * The column here is about thirty-five characters. At that measure ragged-right leaves
 * a lot of the line unused, and justification without hyphenation opens rivers between
 * the words instead. The two together are what make a narrow measure read well, which
 * is why books have always set them that way.
 *
 * [LineBreak.Paragraph] picks line breaks by looking at the paragraph rather than one
 * line at a time. It costs more to lay out, which is affordable now the measuring pass
 * yields between blocks.
 *
 * Applied in blockStyle, so the measuring pass and the renderer break lines identically
 * — they would disagree about where every line ends otherwise.
 */
private fun TextStyle.justified(): TextStyle = copy(
    textAlign = TextAlign.Justify,
    hyphens = Hyphens.Auto,
    lineBreak = LineBreak.Paragraph,
)

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
 * Measures every block at the page width, off the composition thread.
 *
 * Returns null until the pass finishes. It used to run inside `remember`, which meant
 * the first frame of a chapter could not be drawn until every block had been laid out:
 * fine for ten pages, seconds of frozen screen for a long one, and repeated whenever an
 * activity recreation threw composition away.
 *
 * It runs in a coroutine now, yielding every [YIELD_EVERY] blocks so frames keep going
 * while it works. Deliberately on the composition's own dispatcher rather than
 * `Dispatchers.Default`: a dedicated `TextMeasurer` could be built for a worker thread,
 * but the `FontFamily.Resolver` behind it comes from the composition and I have no way
 * to verify off-thread font resolution on the real device from here. Yielding solves
 * the problem that was actually observable — a blocked first frame — without betting on
 * that. Moving it to a worker is the next step once it can be tested on hardware.
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
): List<MeasuredBlock>? = produceState<List<MeasuredBlock>?>(
    initialValue = null,
    document,
    contentWidthPx,
    unitPx,
    typography,
    fontScale,
) {
    // Drop the previous chapter's geometry before measuring the new one, or a stale
    // layout is briefly used to place the new text.
    value = null
    value = measureBlocks(document, contentWidthPx, unitPx, typography, fontScale, measurer)
}.value

/** Blocks measured between yields. Small enough that no single run holds up a frame. */
private const val YIELD_EVERY = 16

private suspend fun measureBlocks(
    document: Document,
    contentWidthPx: Int,
    unitPx: Float,
    typography: LightTypography,
    fontScale: Float,
    measurer: TextMeasurer,
): List<MeasuredBlock> {
    if (contentWidthPx <= 0 || unitPx <= 0f) return emptyList()

    val measured = ArrayList<MeasuredBlock>(document.blocks.size)
    document.blocks.forEachIndexed { index, placed ->
        if (index % YIELD_EVERY == 0) yield()
        measured += measureBlock(placed.block, contentWidthPx, unitPx, typography, fontScale, measurer)
    }
    return measured
}

private fun measureBlock(
    block: Block,
    contentWidthPx: Int,
    unitPx: Float,
    typography: LightTypography,
    fontScale: Float,
    measurer: TextMeasurer,
): MeasuredBlock {
    val spacing = blockSpacingUnits(block) * unitPx

    if (block is Block.Rule) {
        val height = ReaderMetrics.RULE_UNITS * unitPx
        return MeasuredBlock(
            lineTops = listOf(0f),
            lineBottoms = listOf(height),
            charStarts = listOf(0),
            charEnds = listOf(0),
            spacingBefore = spacing,
            keepWhole = true,
        )
    }

    val insetPx = (blockInsetUnits(block) * unitPx).toInt()
    val text = blockAnnotated(block)
    if (text.isEmpty()) {
        return MeasuredBlock(emptyList(), emptyList(), emptyList(), emptyList(), spacing)
    }

    val layout = measurer.measure(
        text = text,
        style = blockStyle(block, typography, fontScale),
        constraints = Constraints(maxWidth = (contentWidthPx - insetPx).coerceAtLeast(1)),
    )

    val lines = layout.lineCount
    return MeasuredBlock(
        lineTops = List(lines) { layout.getLineTop(it) },
        lineBottoms = List(lines) { layout.getLineBottom(it) },
        charStarts = List(lines) { layout.getLineStart(it) },
        charEnds = List(lines) { layout.getLineEnd(it, visibleEnd = false) },
        spacingBefore = spacing,
        keepWhole = block is Block.Heading,
    )
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
                // Clamped to the text actually in hand. Measurement and render build the
                // same string from the same block, so these agree today, but an
                // out-of-range subSequence throws, and a reader that crashes loses the
                // reader's place as well as the page.
                val from = slice.charStart.coerceIn(0, full.length)
                val to = slice.charEnd.coerceIn(from, full.length)
                val hasText = block !is Block.Rule && to > from

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
                        sliceDocumentStart = documentBlock.start + from,
                        charStart = from,
                        charEnd = to,
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
