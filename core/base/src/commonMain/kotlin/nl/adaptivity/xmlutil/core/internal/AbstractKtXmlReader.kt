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

package nl.adaptivity.xmlutil.core.internal

import nl.adaptivity.xmlutil.*
import nl.adaptivity.xmlutil.EventType.*
import nl.adaptivity.xmlutil.core.InputBuffer
import nl.adaptivity.xmlutil.core.impl.NamespaceHolder
import kotlin.jvm.JvmInline
import kotlin.jvm.JvmStatic

/**
 * @param encoding The encoding to record, note this doesn't impact the actual parsing (that is handled in the reader)
 * @param relaxed If `true` ignore various syntax and namespace errors
 * @param expandEntities true if entities are expanded as text, rather than exposed as entities. Note that unresolved entities
 *              will cause an exception in expanding mode.
 */

@XmlUtilInternal
public abstract class AbstractKtXmlReader(
    encoding: String?,
    public val relaxed: Boolean = false,
    public val expandEntities: Boolean = false
) : XmlReader {

    protected abstract val inputBuffer: InputBuffer

    // variables so we don't need readCName to return a pair
    protected var readPrefix: String? = null
    protected var readLocalname: String? = null

    protected var _isWhitespace: Boolean = false

    protected val namespaceHolder: NamespaceHolder = NamespaceHolder()

    public override val depth: Int
        get() = namespaceHolder.depth

    //region Parse state accessors
    override val namespaceDecls: List<Namespace>
        get() = namespaceHolder.namespacesAtCurrentDepth

    override val namespaceContext: IterableNamespaceContext
        get() = namespaceHolder.namespaceContext

    protected var _eventType: EventType? = null //START_DOCUMENT // Already have this state
    public override val eventType: EventType
        get() = when (val et = _eventType) {
            null -> throw IllegalStateException("Not yet started")
            ENTITY_REF if (expandEntities) -> TEXT
            else -> et
        }

    protected var isSelfClosing: Boolean = false

    override fun isWhitespace(): Boolean = when (eventType) {
        TEXT, IGNORABLE_WHITESPACE -> _isWhitespace
        CDSECT -> false
        else -> exception(ILLEGAL_TYPE)
    }

    protected abstract fun get(): String

    override val text: String
        get() = when {
            eventType.isTextElement -> get()
            else -> throw XmlException("The element is not text, it is: $eventType")
        }

    override val piTarget: String
        get() {
            check(eventType == PROCESSING_INSTRUCTION)
            return get().substringBefore(' ')
        }

    override val piData: String
        get() {
            check(eventType == PROCESSING_INSTRUCTION)
            return get().substringAfter(' ', "")
        }

    public fun isEmptyElementTag(): Boolean {
        if (_eventType != START_ELEMENT) exception(ILLEGAL_TYPE)
        return isSelfClosing
    }

    protected var isUnresolvedEntity: Boolean = false

    override fun getNamespacePrefix(namespaceUri: String): String? {
        return namespaceHolder.getPrefix(namespaceUri)
    }

    override fun getNamespaceURI(prefix: String): String? {
        return namespaceHolder.getNamespaceUri(prefix)
    }

    override fun require(type: EventType, namespace: String?, name: String?) {
        if (type != this._eventType || (namespace != null && namespace != elementStack[depth - 1].namespace)
            || (name != null && name != elementStack[depth - 1].localName)
        ) {
            exception("expected: $type {$namespace}$name, found: $_eventType {$namespaceURI}$localName")
        }
    }

    //region Entities
    protected var entityName: String? = null

    public override val isKnownEntity: Boolean
        get() = when (_eventType) {
            ENTITY_REF -> !isUnresolvedEntity

            else -> throw IllegalStateException("isKnownEntity is only relevant for entities")
        }
    //endregion Entities

    //region Document declaration
    public override var version: String? = null
        @XmlUtilInternal
        protected set

    public override var standalone: Boolean? = null
        @XmlUtilInternal
        protected set

    override var encoding: String? = encoding
        @XmlUtilInternal
        protected set
    //endregion Document declaration

    //region Name
    public override val localName: String
        get() = when (_eventType) {
            ENTITY_REF if (!expandEntities) -> entityName ?: throw XmlException("Missing entity name")
            START_ELEMENT, END_ELEMENT -> elementStack[depth - 1].localName ?: throw XmlException("Missing local name")
            else -> throw IllegalStateException("Local name not accessible outside of element tags: $_eventType")
        }

    public override val namespaceURI: String
        get() = when (_eventType) {
            START_ELEMENT, END_ELEMENT -> elementStack[depth - 1].namespace ?: throw XmlException(
                "Missing namespace",
                extLocationInfo
            )

            else -> throw IllegalStateException("Local name not accessible outside of element tags")
        }

    public override val prefix: String
        get() = when (_eventType) {
            START_ELEMENT, END_ELEMENT -> elementStack[depth - 1].prefix ?: ""

            else -> throw IllegalStateException("Local name not accessible outside of element tags")
        }
    //endregion Name
    //endregion Parse state accessors

    //region Location info
    protected abstract var line: Int
    public val column: Int get() = offset - lastColumnStart + 1
    protected abstract var lastColumnStart: Int
    protected abstract val offset: Int

    protected fun incCol() {
//        offset += 1
    }

    protected fun incLine(offsetAdd: Int = 1) {
        val newOffset = offset + offsetAdd
//        offset = newOffset
        lastColumnStart = newOffset
        line += 1
    }

    override val extLocationInfo: XmlReader.LocationInfo
        get() = XmlReader.ExtLocationInfo(col = column, line = line, offset = offset)

    public fun getLineNumber(): Int {
        return line
    }

    public fun getColumnNumber(): Int {
        return column
    }

    //endregion Location info

    //region Attributes
    override var attributeCount: Int = 0
        protected set

    override fun getAttributeNamespace(index: Int): String {
        return attribute(index).namespace!!
    }

    override fun getAttributeLocalName(index: Int): String {
        return attribute(index).localName!!
    }

    override fun getAttributePrefix(index: Int): String {
        return attribute(index).prefix ?: ""
    }

    override fun getAttributeValue(index: Int): String {
        return attribute(index).value!!
    }

    override fun getAttributeValue(nsUri: String?, localName: String): String? {
        for (attrIdx in 0 until attributeCount) {
            val attr = attribute(attrIdx)
            if (attr.localName == localName && (nsUri == null || attr.namespace == nsUri)) {
                return attr.value
            }
        }
        return null
    }

    private var attrData: Array<String?> = arrayOfNulls(16)


    protected fun clearAttributes() {
        val oldSize = attributeCount
        if (oldSize > 0) {
            attrData.fill(null, 0, oldSize * 4)
        }
        attributeCount = 0
    }

    protected fun shrinkAttributeBuffer(newSize: Int) {
        attrData.fill(null, newSize * 4, attributeCount * 4)
        attributeCount = newSize
    }

    private fun ensureAttributeBufferCapacity(required: Int) {
        val requiredSize = required * 4
        val oldData = attrData
        if (oldData.size >= requiredSize) return

        attrData = oldData.copyOf(requiredSize + 16)
    }

    protected fun addUnresolvedAttribute(attrPrefix: String?, attrLocalName: String, attrValue: String) {
        val oldSize = attributeCount
        val newSize = if (oldSize < 0) 1 else oldSize + 1
        attributeCount = newSize

        ensureAttributeBufferCapacity(newSize)
        var i = newSize * 4 - 4

        val d = attrData
        d[i++] = null
        d[i++] = attrPrefix
        d[i++] = attrLocalName
        d[i] = attrValue
    }

    protected fun copyAttributeNotNS(fromIdx: Int, toIdx: Int) {
        attrData.copyInto(attrData, toIdx * 4 + 1, fromIdx * 4 + 1, fromIdx * 4 + 4)
    }

    protected fun attribute(index: Int): AttributeDelegate = AttributeDelegate(index)

    protected var AttributeDelegate.namespace: String?
        get() {
            if (index >= attributeCount) throw IndexOutOfBoundsException()
            return attrData[index * 4]
        }
        set(value) {
            attrData[index * 4] = value
        }

    protected var AttributeDelegate.prefix: String?
        get() {
            if (index >= attributeCount) throw IndexOutOfBoundsException()
            return attrData[index * 4 + 1]
        }
        set(value) {
            attrData[index * 4 + 1] = value
        }

    protected var AttributeDelegate.localName: String?
        get() {
            if (index >= attributeCount) throw IndexOutOfBoundsException()
            return attrData[index * 4 + 2]
        }
        set(value) {
            attrData[index * 4 + 2] = value
        }

    protected var AttributeDelegate.value: String?
        get() {
            if (index >= attributeCount) throw IndexOutOfBoundsException()
            return attrData[index * 4 + 3]
        }
        set(value) {
            attrData[index * 4 + 3] = value
        }

    @JvmInline
    protected value class AttributeDelegate(public val index: Int)


    private inner class AttributesCollection {

    }

    //endregion Attributes

    //region Elements
    protected var elementStack: ElementStack = ElementStack()


    private var elementData: Array<String?> = arrayOfNulls(48)

    private fun element(idx: Int) = ElementDelegate(idx)

    protected var ElementDelegate.namespace: String?
        get() {
            if (index >= depth) throw IndexOutOfBoundsException()
            return elementData[index * 3]
        }
        set(value) {
            elementData[index * 3] = value
        }

    protected var ElementDelegate.prefix: String?
        get() {
            if (index >= depth) throw IndexOutOfBoundsException()
            return elementData[index * 3 + 1]
        }
        set(value) {
            elementData[index * 3 + 1] = value
        }

    protected var ElementDelegate.localName: String?
        get() {
            if (index >= depth) throw IndexOutOfBoundsException()
            return elementData[index * 3 + 2]
        }
        set(value) {
            elementData[index * 3 + 2] = value
        }

    @JvmInline
    protected value class ElementDelegate(public val index: Int)

    protected inner class ElementStack {

        public operator fun get(idx: Int): ElementDelegate = element(idx)

        public fun ensureCapacity(required: Int) {
            val requiredCapacity = required * 3 // three slots per element
            if (elementData.size >= requiredCapacity) return

            elementData = elementData.copyOf(requiredCapacity + 12)
        }

    }
    //endregion Elements

    //region State
    protected var state: State = State.BEFORE_START

    override val isStarted: Boolean
        get() = state != State.BEFORE_START

    @XmlUtilInternal
    protected enum class State {
        /** Parsing hasn't started yet */
        BEFORE_START,

        /** At or past parsing the xml header */
        START_DOC,

        /** At or past parsing the document type definition */
        DOCTYPE_DECL,

        /** Parsing the main document element */
        BODY,

        /** At end of main document element end tag, or after it*/
        POST,

        /** At end of file */
        EOF
    }
    //endregion State

    //region Output

    /** Add the given character (16-bit) to the output buffer if it is > 0 */
    protected fun pushChar(cp: Int) {
        when {
            cp < 0 -> error(UNEXPECTED_EOF)
            else -> pushChar(cp.toChar())
        }
    }

    /** Add the given character to the output buffer */
    protected abstract fun pushChar(c: Char)

    /** Add the entire given string to the output buffer */
    protected abstract fun push(s: CharSequence)

    /**
     * Push the text until the [delimiter] to the output buffer.
     */
    protected abstract fun pushText(delimiter: Char)

    /**
     * Specialisation of pushText that does not recognize whitespace (thus able to be used at that point)
     * @param delimiter The "stopping" delimiter
     * @param resolveEntities Whether entities should be resolved directly (in attributes) or exposed as entity
     *                        references (content text if expandEntities is false).
     */
    protected abstract fun pushRegularText(delimiter: Char, resolveEntities: Boolean)

    /**
     * Remove the last character from the output buffer
     */
    protected abstract fun popOutput()

    //endregion

    //region Error handling

    protected var error: String? = null

    protected fun error(desc: String) {
        if (relaxed) {
            if (error == null) error = "ERR: $desc"
        } else exception(desc)
    }

    protected fun exception(desc: String): Nothing {
        throw XmlException(
            when {
                desc.length < 100 -> desc
                else -> "${desc.take(100)}\n"
            },
            this
        )
    }

    protected fun pushErrorComment(): Boolean = when (val e = error){
        null -> false
        else -> {
            push("At: ")
            push(extLocationInfo.toString())
            push(" - ")
            push(e)

            this.error = null
            _eventType = COMMENT
            true
        }
    }

    //endregion Error handling

    //region Parsing

    //region helpers
    private fun adjustNsp(prefix: String?, localName: String) {
        var hasActualAttribute = false

        // Loop through all attributes to collect namespace attributes and split name into prefix/localName.
        // Namespaces will not be set yet (as the namespace declaring attribute may be afterwards)
        var attrIdx = 0
        while (attrIdx < attributeCount) {
            val attr = attribute(attrIdx++)

            val aLocalName: String = attr.localName!!
            val aPrefix: String? = attr.prefix

            when (aPrefix) {
                "xmlns" -> {
                    namespaceHolder.addPrefixToContext(aLocalName, attr.value)
                    if (attr.value == "") error("illegal empty namespace")

                    attr.localName = null // mark for deletion
                }

                null if aLocalName == "xmlns" -> {
                    namespaceHolder.addPrefixToContext("", attr.value)
                    attr.localName = null // mark for deletion
                }

                else -> hasActualAttribute = true
            }
        }
        if (hasActualAttribute) {
            var attrInIdx = 0
            var attrOutIdx = 0

            // This gradually copies the attributes to remove namespace declarations
            // use while loop as we need the final size afterwards
            while (attrInIdx < attributeCount) {
                val attrIn = attribute(attrInIdx++)
                val attrLocalName = attrIn.localName
                if (attrLocalName != null) {
                    val attrOut = attribute(attrOutIdx++)

                    if (attrIn != attrOut) {
                        copyAttributeNotNS(attrIn.index, attrOut.index)
                    }

                    val attrPrefix = attrIn.prefix

                    if (attrPrefix == "") {
                        error("illegal attribute name: ${fullname(attrPrefix, attrLocalName)} at $this")
                        attrOut.namespace = "" // always true for null namespace
                    } else if (attrPrefix != null) {
                        val attrNs = namespaceHolder.getNamespaceUri(attrPrefix)
                        if (attrNs == null) {
                            elementStack[depth - 1].also {
                                it.localName = localName
                                it.prefix = prefix
                                it.namespace = "<not yet set>"
                            }
                            error("Undefined Prefix: $attrPrefix in $this")
                        }
                        attrOut.namespace = attrNs
                    } else {
                        attrOut.namespace = ""
                    }
                }
            }

            if (attrInIdx != attrOutIdx) {
                shrinkAttributeBuffer(attrOutIdx)
            }

        } else {
            shrinkAttributeBuffer(0)
        }

        val ns = namespaceHolder.getNamespaceUri(prefix ?: "")
            ?: XMLConstants.NULL_NS_URI.also { if (prefix != null) error("undefined prefix: $prefix") }

        val d = depth - 1
        elementStack[d].prefix = prefix
        elementStack[d].localName = localName
        elementStack[d].namespace = ns
    }

    /**
     * Empty the output buffer.
     */
    protected abstract fun resetOutputBuffer()

    /**
     * Set the current output buffer to the given output
     */
    protected abstract fun setOutputBuffer(output: CharSequence)

    protected abstract fun readName(): String

    /**
     * Read a cName. This will update the [readPrefix] and [readLocalname] properties to
     * avoid further allocations.
     */
    protected abstract fun readCName()

    /**
     * Skip reading whitespace
     */
    protected fun skipWS() {
        while (true) {
            val c = peek()
            if (c == -1 || !isXmlWhitespace(c.toChar())) break // More sane

            readAssert(c.toChar())
        }
    }

    /**
     * Specialisation of pushText that does not recognize whitespace (thus able to be used at that point)
     * @param delimiter The "stopping" delimiter
     */
    protected abstract fun pushAttributeValue(delimiter: Char)

    /** Push attribute delimited by whitespace */
    protected abstract fun pushWSDelimAttrValue()

    /**
     * Read the next character from the input. This will read UTF-16 values not codepoints.
     * This function will also do line ending normalization (per spec) and will also need to
     * do line/column updating.
     */
    protected abstract fun read(): Int

    /**
     * Read the next character and assert it is the expected character.
     */
    protected fun readAssert(c: Char) {
        val a = read()
        if (a != c.code) error("expected: '$c' actual: '${a.toChar()}'")
    }

    protected open fun readAssert(s: String, errorMessage: (Char) -> String ) {
        for (c in s) {
            val d = read()
            if (c.code != d) error(errorMessage(c))
        }
    }

    protected fun readAssert(s: String): Unit = readAssert(s) { c ->
        "Found unexpected character '$c' while parsing '$s' at offset $offset"
    }

    /**
     * Read a character and add it to the output. This must do line ending normalization
     * and handling.
     */
    protected abstract fun readAndPush(): Char

    /** Does never read more than needed  */
    protected abstract fun peek(): Int
    protected abstract fun peek(pos: Int): Int

    //endregion

    /** Sets name and attributes  */
    protected fun parseStartTag(xmldecl: Boolean) {
        val prefix: String?
        val localName: String
        resetOutputBuffer()
        if (xmldecl) {
            prefix = null
            localName = readName()
        } else {
            readCName()
            prefix = readPrefix
            localName = readLocalname!!
        }
        clearAttributes()
        while (true) {
            skipWS()
            when (val c = peek()) {
                '?'.code -> {
                    if (!xmldecl) error("? found outside of xml declaration")
                    readAssert('?')
                    readAssert('>')
                    return
                }

                '/'.code -> {
                    if (xmldecl) error("/ found to close xml declaration")
                    isSelfClosing = true
                    readAssert('/')
                    if (isXmlWhitespace(peek().toChar())) {
                        error("ERR: Whitespace between empty content tag closing elements")
                        while (isXmlWhitespace(peek().toChar())) {
                            val _ = read()
                        }
                    }
                    readAssert('>')
                    break
                }

                '>'.code -> {
                    if (xmldecl) error("xml declaration must be closed by '?>', not '>'")
                    readAssert('>')
                    break
                }

                -1 -> {
                    error(UNEXPECTED_EOF)
                    return
                }

                ' '.code, '\t'.code, '\n'.code, '\r'.code -> {
                    val _ = next() // ignore whitespace
                }

                else -> when {
                    isNameStartChar(c.toChar()) -> {
                        resetOutputBuffer()
                        readCName()
                        val aLocalName = readLocalname!!

                        if (aLocalName.isEmpty()) {
                            error("attr name expected")
                            break
                        }
                        skipWS()
                        if (peek() != '='.code) {
                            val fullname = fullname(readPrefix, aLocalName)
                            error("Attr.value missing in $fullname '='. Found: ${peek().toChar()}")

                            addUnresolvedAttribute(readPrefix, aLocalName, fullname)
                        } else {
                            readAssert('=')
                            skipWS()
                            when (val delimiter = peek()) {
                                '\''.code, '"'.code -> {
                                    readAssert(delimiter.toChar())
                                    // This is an attribute, we don't care about whitespace content
                                    resetOutputBuffer()
                                    pushAttributeValue(delimiter.toChar())
                                    readAssert(delimiter.toChar())
                                }

                                else -> {
                                    error("attr value delimiter missing!")
                                    resetOutputBuffer()
                                    pushWSDelimAttrValue()
                                }
                            }

                            addUnresolvedAttribute(readPrefix, aLocalName, get())
                        }
                    }

                    else -> {
                        val fullName = fullname(prefix, localName)
                        error("unexpected character in tag($fullName): '${c.toChar()}'")
                        readAssert(c.toChar())
                    }
                }
            }

        }

        val d = depth
        namespaceHolder.incDepth()
        elementStack.ensureCapacity(depth)

        if (true) {
            adjustNsp(prefix, localName)
        } else {
            elementStack[d].namespace = ""
        }
    }

    protected fun parseCData() {
        readAssert('<') // <
        readAssert('!') // '['
        readAssert("[CDATA[")

        inputBuffer.startOrResumeCopySequence()
        resetOutputBuffer()
        var c: Char

        do {
            c = inputBuffer.readChar()
        } while (c != ']' || ! inputBuffer.peek("]>"))
        popOutput() // ']'
        readAssert(']') // ']'
        readAssert('>') // '>'
        return
    }

    /** precondition: &lt! consumed  */
    protected fun parseDoctype() {
        var nesting = 1
        var quote: Char? = null

        while (true) {
            val i = read()
            when (i) {
                '\''.code,
                '"'.code -> when (quote) {
                    null -> quote = i.toChar()
                    i.toChar() -> quote = null
                }

                '-'.code -> if (quote == '!') {
                    pushChar('-')

                    var c = read()
                    pushChar(c)
                    if (c != '-'.code) continue

                    c = read()
                    pushChar(c)
                    if (c != '>'.code) continue

                    quote = null
                }

                '['.code -> if (quote == null && nesting == 1) ++nesting

                ']'.code -> if (quote == null) {
                    pushChar(']')
                    val c = read()
                    pushChar(c)
                    if (c != '>'.code) continue
                    if (nesting != 2) error("Invalid nesting of document type declaration: $nesting")
                    return
                }

                '<'.code -> if (quote == null) {
                    if (nesting < 2) error("Doctype with internal subset must have an opening '['")

                    pushChar('<')
                    var c = read()
                    pushChar(c)
                    if (c != '!'.code) {
                        nesting++; continue
                    }

                    c = read()
                    pushChar(c)
                    if (c != '-'.code) {
                        nesting++; continue
                    }

                    c = read()
                    pushChar(c)
                    if (c != '-'.code) {
                        nesting++; continue
                    }
                    quote = '!' // marker for comment
                }

                '>'.code -> if (quote == null) {
                    when (--nesting) {
                        1 -> error("Missing closing ']' for doctype")
                        0 -> return
                    }
                }
            }
            pushChar(i)
        }
    }

    protected abstract fun pushEntity(expandEntities: Boolean = this.expandEntities)

    protected fun parsePI() {
        readAssert('<') // <
        readAssert('?') // '?'
        resetOutputBuffer()
        readTagContentUntil('?')
    }

    protected fun readTagContentUntil(delim: Char) {
        var c: Char
        do {
            c = readAndPush()
        } while (c != delim || peek() != '>'.code)
        popOutput()
        readAssert('>') // '>'
        return
    }

    protected fun parseUnexpectedOrWS(eventType: EventType) {
        when (eventType) {
            START_DOCUMENT -> {
                error("Unexpected START_DOCUMENT in state $state")
                parseStartTag(true) // parse it to ignore it
            }

            START_ELEMENT -> {
                error("Unexpected start tag after document body")
                parseStartTag(false)
            }

            END_ELEMENT -> {
                error("Unexpected end tag outside of body")
                parseEndTag()
            }

            ATTRIBUTE,
            IGNORABLE_WHITESPACE,
            COMMENT -> throw UnsupportedOperationException("Comments/WS are always allowed - they may start the document tough")

            TEXT -> {
                pushText('<')
                when {
                    _isWhitespace -> _eventType = IGNORABLE_WHITESPACE
                    else -> error("Non-whitespace text where not expected: '${text}'")
                }
            }

            CDSECT -> {
                error("CData sections are not supported outside of the document body")
                parseCData()
            }

            DOCDECL -> {
                error("Document declarations are not supported outside the preamble")
                parseDoctype()
            }

            END_DOCUMENT -> {
                error("End of document before end of document element")
            }

            ENTITY_REF -> {
                error("Entity reference outside document body")
                pushEntity()
            }

            PROCESSING_INSTRUCTION -> {
                error("Processing instruction inside document body")
                parsePI()
            }
        }
    }


    override fun close() {
        //NO-Op
    }

    override fun next(): EventType {
        _isWhitespace = true

        // reset the output buffer
        resetOutputBuffer()

        when (state) {
            State.BEFORE_START -> nextImplDocStart()

            State.START_DOC,
            State.DOCTYPE_DECL -> nextImplPreamble()

            State.BODY -> nextImplBody()
            State.POST -> nextImplPost()
            State.EOF -> error("Reading past end of file")
        }
//        assert((offset - srcBufPos) % BUF_SIZE == 0) { "Offset error: ($offset - $srcBufPos) % $BUF_SIZE != 0" }
        return when (val et = eventType) {
            ENTITY_REF if (expandEntities) -> TEXT
            else -> et
        }
    }

    override fun hasNext(): Boolean {
        return _eventType != END_DOCUMENT
    }

    override fun nextTag(): EventType {
        var et: EventType
        do {
            et = next()
        } while (et.isIgnorable || (et == TEXT && _isWhitespace))

        if (et != END_ELEMENT && et != START_ELEMENT) exception("unexpected type")
        return et
    }


    protected fun nextImplDocStart() {
        val eventType = peekType()
        if (eventType == START_DOCUMENT) {
            readAssert('<') // <
            readAssert('?') // ?
            parseStartTag(true)
            if (attributeCount < 1 || "version" != attribute(0).localName) error("version expected")
            version = attribute(0).value
            var pos = 1
            if (pos < attributeCount && "encoding" == attribute(1).localName) {
                encoding = attribute(1).value
                pos++
            }
            if (pos < attributeCount && "standalone" == attribute(pos).localName) {
                when (val st = attribute(pos).value) {
                    "yes" -> standalone = true
                    "no" -> standalone = false
                    else -> error("illegal standalone value: $st")
                }
                pos++
            }
            if (pos != attributeCount) error("illegal xmldecl")
            _isWhitespace = true
        } // if it is not a doc start synthesize an event.
        _eventType = START_DOCUMENT
        state = State.START_DOC
        return
    }

    /**
     * common base for next and nextToken. Clears the state, except from
     * txtPos and whitespace. Does not set the type variable  */
    private fun nextImplPreamble() {
        if(pushErrorComment()) return

        val eventType = peekType()
        _eventType = eventType
        when (eventType) {
            PROCESSING_INSTRUCTION -> parsePI()

            START_ELEMENT -> {
                state = State.BODY // this must start the body
                readAssert('<')
                parseStartTag(false)
            }

            DOCDECL -> {
                readAssert("<!DOCTYPE")
                parseDoctype()
            }

            COMMENT -> parseComment()

            else -> parseUnexpectedOrWS(eventType)
        }
    }

    /**
     * common base for next and nextToken. Clears the state, except from
     * txtPos and whitespace. Does not set the type variable  */
    private fun nextImplBody() {
        // Depth is only decreased *after* the end element.
        if (_eventType == END_ELEMENT) namespaceHolder.decDepth()

        // degenerated needs to be handled before error because of possible
        // processor expectations(!)
        if (isSelfClosing) {
            isSelfClosing = false
            _eventType = END_ELEMENT
            if (depth == 1) state = State.POST
            return
        }

        error?.let { e ->
            push(e)

            this.error = null
            _eventType = COMMENT
            return
        }
        val lastEvent = _eventType
        val eventType = peekType()
        _eventType = eventType
        when (eventType) {

            COMMENT -> parseComment()

            ENTITY_REF if (expandEntities) -> pushRegularText('<', true)
            ENTITY_REF -> pushEntity()

            START_ELEMENT -> {
                readAssert('<')
                parseStartTag(false)
            }

            END_ELEMENT -> {
                parseEndTag()
                if (depth == 1) state = State.POST
            }

            TEXT -> if (lastEvent == ENTITY_REF) { // Entity refs are part of text, so don't
                // consider the following text whitespace at all
                pushRegularText('<', expandEntities)
            } else {
                pushText('<')
                if (_isWhitespace) _eventType = IGNORABLE_WHITESPACE
            }

            CDSECT -> parseCData()

            else -> parseUnexpectedOrWS(eventType)

        }
    }

    /**
     * Parse only the post part of the document. *misc* = Comment | PI | S
     */
    private fun nextImplPost() {
        if (_eventType == END_ELEMENT) namespaceHolder.decDepth()

        // degenerated needs to be handled before error because of possible
        // processor expectations(!)
        if (isSelfClosing) {
            isSelfClosing = false
            _eventType = END_ELEMENT
            return
        }
        error?.let { e ->
            push(e)

            this.error = null
            _eventType = COMMENT
            return
        }

        val eventType = peekType()
        _eventType = eventType
        when (eventType) {
            PROCESSING_INSTRUCTION -> parsePI()

            COMMENT -> parseComment()

            END_DOCUMENT -> {
                state = State.EOF
                return
            }

            else -> parseUnexpectedOrWS(eventType)
        }
    }

    private fun parseComment() {
        readAssert("<!--")

        inputBuffer.startOrResumeCopySequence()
        inputBuffer.addDelimitedToCopySequence("--")
        if (inputBuffer.readChar() != '>') {
            error("XML Comments may not contain inner --, or be terminated by '--->'")
        }
        setOutputBuffer(inputBuffer.finalizeCopySequence())

        return
    }

    protected abstract fun parseEndTag()

    private fun peekType(): EventType {
        return when (peek()) {
            -1 -> END_DOCUMENT
            '&'.code -> ENTITY_REF
            '<'.code -> when (peek(1)) {
                '/'.code -> END_ELEMENT
                '?'.code -> when {
                    // order backwards to ensure
                    peek(2) == 'x'.code && peek(3) == 'm'.code &&
                            peek(4) == 'l'.code && !isNameCodepoint(peek(5)) ->
                        START_DOCUMENT

                    else -> PROCESSING_INSTRUCTION
                }

                '!'.code -> when (peek(2)) {
                    '-'.code -> COMMENT
                    '['.code -> CDSECT
                    else -> DOCDECL
                }

                else -> START_ELEMENT
            }

            else -> TEXT
        }
    }

    //endregion Parsing

    internal companion object {
        internal const val UNEXPECTED_EOF = "Unexpected EOF"
        internal const val ILLEGAL_TYPE = "Wrong event type"


        @JvmStatic
        protected fun fullname(prefix: String?, localName: String): String = when (prefix) {
            null -> localName
            else -> "$prefix:$localName"
        }
    }
}
