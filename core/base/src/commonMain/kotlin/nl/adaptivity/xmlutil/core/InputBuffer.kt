/*
 * Copyright (c) 2026.
 *
 * This file is part of xmlutil.
 *
 * This file is licenced to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance
 * with the License.  You should have  received a copy of the license
 * with the source distribution. Alternatively, you may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package nl.adaptivity.xmlutil.core

import nl.adaptivity.xmlutil.XmlUtilInternal

@XmlUtilInternal
public object CopySequenceMarker

@IgnorableReturnValue
internal inline fun InputBuffer.createCopySequence(block: context(CopySequenceMarker) () -> Unit): CharSequence {
    startOrResumeCopySequenceXX()
    var r: CharSequence
    try {
        context(CopySequenceMarker) { block() }
    } finally {
        r = finalizeCopySequenceXX()
    }
    return r
}

@XmlUtilInternal
public interface InputBuffer {
    public val offset: Int

    public val line: Int

    public val lastColumnStart: Int

    public val column: Int get() = offset - lastColumnStart

    /**
     * Ensure that there is an active copy sequence. This does nothing if the sequence is already
     * active. If it is paused, it will be resumed. If there is no sequence it will be started.
     */
    public fun ensureActiveCopySequence()

    /**
     * Mark the start or resumption of a sequence that will be copied to string later. By default
     * this will just store the start position. It however also triggers handling of special cases,
     * that may trigger the use of a StringBuilder to store the sequence:
     *  - A line ending involving a '\r' (must be exposed as '\n')
     *  - Buffer swaps
     */
    public fun startOrResumeCopySequenceXX() {
        ensureActiveCopySequence()
    }

    context(_: CopySequenceMarker)
    public fun resumeCopySequence()

    /**
     * Finish/finalise a copy sequence. This means it cannot be appended to anymore
     */
    public fun finalizeCopySequenceXX(): CharSequence

    /**
     * Pause a copy sequence. This means that reading will not add further tokens to the sequence.
     */
    context(_: CopySequenceMarker)
    public open fun pauseCopySequence() {
        ensurePausedCopySequence()
    }

    /**
     * Pause a copy sequence if it exists. If it does exist create one and then pause it.
     */
    public fun ensurePausedCopySequence()

    /**
     * Read tokens into the sequence up to the expected delimiters. The delimiters will
     * also be consumed.
     *
     * If an end of stream is encountered this function should throw an exception.
     *
     * @param delimiter The expected delimiter.
     * @param pauseOnDelimiter Pause the sequence on observation of the delimiter.
     * @param consumeDelimiter If true, the delimiter will be consumed.
     */
    context(_: CopySequenceMarker)
    public fun addDelimitedToCopySequence(delimiter: String, pauseOnDelimiter: Boolean = true, consumeDelimiter: Boolean = true)

    /**
     * Add the given character to the copy sequence. This requires an active copy sequence.
     * It will force buffering of the underlying read characters if needed.
     */
    context(_: CopySequenceMarker)
    public fun addToCopySequence(char: Char)

    context(_: CopySequenceMarker)
    public fun addToCopySequence(seq: CharSequence) {
        seq.forEach { addToCopySequence(it) }
    }

    /**
     * Read a range of characters from the input buffer into a sequence.
     * Note that this requires the characters to be still in the buffer. It is intended
     * for handling peeks of entity reference names.
     *
     * Note the caller is required to ensure there are no '\r' characters present
     */
    public fun readSubRange(start: Int, end: Int): CharSequence

    /**
     * Read the subrange indicated by the ofsets and append it to the (active or paused) output
     * buffer.
     */
    context(_: CopySequenceMarker)
    public fun appendSubRangeToSequence(start: Int, end: Int) {
        for (c in readSubRange(start, end)) addToCopySequence(c)
    }

    /** Try to read the next character without increasing the position  */
    public fun peek(): Int = peek(0)

    public fun peekChar(): Char {
        val c = peek()
        if (c < 0) error("Unexpected EOF")
        return c.toChar()
    }

    public fun peekChar(offset: Int): Char {
        val c = peek(offset)
        if (c < 0) error("Unexpected EOF")
        return c.toChar()
    }

    /** Determine whether the next character is the expected character, but do not consume it. */
    public fun peek(expected: Char): Boolean = peek(0, expected)

    /**
     * Try to read the next character starting at the given offset.
     * @param offset The offset to use. Note that large offset may break due to missing checks
     */
    public fun peek(offset: Int): Int

    /**
     * Determine whether the following characters match the expected character sequence starting
     * at the given offset.
     *
     * @param expected The expected character sequence. Note that this may not contain '\r'
     * characters as the implementation does not normalize end-of-line.
     */
    public fun peek(offset: Int, expected: CharSequence): Boolean {
        return expected.indices.all { peek(offset + it) == expected[it].code }
    }

    /**
     * Determine whether the following characters match the expected character sequence starting
     * at the given offset.
     *
     * @param expected The expected character. Note that this may not be '\r'
     * characters as the implementation does not normalize end-of-line.
     */
    public fun peek(offset: Int, expected: Char): Boolean {
        return peek(offset) == expected.code
    }

    /**
     * Determine whether the following characters match the expected character sequence)
     *
     * @param expected The expected character sequence. Note that this may not contain '\r'
     * characters as the implementation does not normalize end-of-line.
     */
    public fun peek(expected: CharSequence): Boolean = peek(0, expected)

    /**
     * Skip the given amount of characters. This function should not be used to skip over line endings.
     */
    public fun skip(count: Int)

    /** Does never read more than needed  */
    public fun read(): Int

    public fun readChar(): Char {
        val c = read()
        if (c < 0) error("Unexpected EOF")
        return c.toChar()
    }

    /**
     * Read the current character to the copy buffer.
     */
    context(_: CopySequenceMarker)
    public fun readToCopyBuffer()
}
