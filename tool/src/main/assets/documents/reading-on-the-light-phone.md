# Reading on the Light Phone

Lightmark shows Markdown and plain text, and lets you keep what matters.
There is no feed here, nothing to refresh, and no reason to open it twice
in an hour.

## Turning pages

Swipe to go forward, swipe back to return. The text is set in pages rather
than a scroll because this screen is slower to redraw than a smartphone's:
a page turn is one repaint, where scrolling is a continuous smear.

The page sits in the same frame every time, so only the words move.

## Highlighting

Press and hold on a word, then drag. Whatever the drag covers becomes the
highlight, and it is released when you lift your finger. Press and hold
again on a highlight to remove it.

Highlights are stored by character position rather than by line or page,
so they stay on the right words when the text reflows — a different type
size, or a turn of the screen.

A highlight is drawn by **inverting** the text rather than washing it in
colour. LightOS gives a tool three colours: a background, a foreground,
and a dimmer foreground for secondary text. Inversion is the honest
equivalent of a highlighter on a screen like this one.

## Everything else

The hairline along the bottom edge is how far through you are. There is no
page number, because a number invites checking.

To leave, press back. That is the whole interface: swipe, press and hold,
and back.

## Adding your own documents

Files live in the tool's `documents` folder. Anything ending in `.md`,
`.markdown` or `.txt` is picked up.

Converting a PDF before you copy it across works better than reading the
PDF itself. The only renderer available to a tool returns a page as a
bitmap with no text layer, which means no selection and no reflow — and a
letter-sized page on a 3.92" screen is a pan-and-zoom exercise either way.

    pandoc paper.pdf -t markdown -o paper.md

## What is supported

- Headings, from one to six hashes
- Paragraphs, with soft line wrapping
- Bullet lists and numbered lists
- Blockquotes
- Fenced and indented code
- Thematic breaks
- Inline `code`, **bold**, *italic*, and [links](https://thelightphone.com)

Deliberately left out: tables, nested lists, reference links, raw HTML and
footnotes. A reader for a phone this size is better for leaving them out.

> The point of a tool like this is to finish something and put the phone
> down again.

---

That is the whole feature set. Happy reading.
