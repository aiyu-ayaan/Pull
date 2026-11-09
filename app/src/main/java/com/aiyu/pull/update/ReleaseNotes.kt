package com.aiyu.pull.update

/** One drawable piece of a release body, which `scripts/release-notes.mjs` writes as `### Heading` + `* bullet` lines. */
sealed interface NoteBlock {
    data class Heading(val text: String) : NoteBlock
    data class Bullet(val text: String) : NoteBlock
    data class Paragraph(val text: String) : NoteBlock
}

object ReleaseNotes {
    /**
     * Just the shapes release notes use: headings, bullets, paragraphs. Inline marks are left in the
     * text for the renderer. Table rules (`| --- |`) and fences are dropped rather than drawn as noise.
     */
    fun parse(text: String): List<NoteBlock> {
        val blocks = mutableListOf<NoteBlock>()
        var inFence = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("```")) { inFence = !inFence; continue }
            when {
                inFence || line.isEmpty() || line.matches(TABLE_RULE) -> Unit
                line.startsWith("#") -> blocks += NoteBlock.Heading(line.trimStart('#').trim())
                line.startsWith("* ") || line.startsWith("- ") -> blocks += NoteBlock.Bullet(line.drop(2).trim())
                else -> blocks += NoteBlock.Paragraph(line)
            }
        }
        return blocks
    }

    private val TABLE_RULE = Regex("""^\|?[\s|:-]+\|?$""")
}
