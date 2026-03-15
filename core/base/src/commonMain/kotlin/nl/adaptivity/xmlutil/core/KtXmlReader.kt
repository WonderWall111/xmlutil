/*
 * Copyright (c) 2024-2026.
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

@file:MustUseReturnValues

package nl.adaptivity.xmlutil.core

import nl.adaptivity.xmlutil.*
import nl.adaptivity.xmlutil.EventType.*
import nl.adaptivity.xmlutil.core.impl.EntityMap
import nl.adaptivity.xmlutil.core.impl.NamespaceHolder
import nl.adaptivity.xmlutil.core.impl.multiplatform.Reader
import nl.adaptivity.xmlutil.core.impl.multiplatform.assert
import nl.adaptivity.xmlutil.core.impl.multiplatform.ifAssertions
import nl.adaptivity.xmlutil.core.internal.*
import kotlin.jvm.JvmInline
import kotlin.jvm.JvmStatic

@ExperimentalXmlUtilApi
/**
 * @param inputBuffer A helper buffer to handle parsing/chunking inputs and collating outputs
 *          while trying to avoid character copying. It also allows for string/utf8 based inputs.
 * @param encoding The encoding to record, note this doesn't impact the actual parsing (that is handled in the reader)
 * @param relaxed If `true` ignore various syntax and namespace errors
 * @param expandEntities true if entities are expanded as text, rather than exposed as entities. Note that unresolved entities
 *              will cause an exception in expanding mode.
 */
public class KtXmlReader(
    inputBuffer: InputBuffer,
    encoding: String?,
    public val relaxed: Boolean = false,
    public val expandEntities: Boolean = false,
) : XmlReader {

    internal constructor(
        reader: Reader,
        encoding: String?,
        relaxed: Boolean = false,
        expandEntities: Boolean = false
    ) : this(SwappedInputBuffer(reader), encoding, relaxed, expandEntities)

    public constructor(reader: Reader, relaxed: Boolean = false) :
            this(reader, null, relaxed)

    public constructor(reader: Reader, expandEntities: Boolean, relaxed: Boolean = false) :
            this(reader, null, relaxed, expandEntities)


    private var inputBuffer: InputBuffer = inputBuffer
        private set

    // must have different name than function.
    @Suppress("PropertyName")
    private var _isWhitespace: Boolean = false

    override fun isWhitespace(): Boolean = when (eventType) {
        TEXT, IGNORABLE_WHITESPACE -> _isWhitespace
        CDSECT -> false
        else -> exception(ILLEGAL_TYPE)
    }

    private val namespaceHolder: NamespaceHolder = NamespaceHolder()

    public override val depth: Int
        get() = namespaceHolder.depth

    @XmlUtilInternal
    internal val entityMap: EntityMap = EntityMap()

    //region Parse state accessors
    override val namespaceDecls: List<Namespace>
        get() = namespaceHolder.namespacesAtCurrentDepth

    override val namespaceContext: IterableNamespaceContext
        get() = namespaceHolder.namespaceContext

    private var _eventType: EventType? = null //START_DOCUMENT // Already have this state
    public override val eventType: EventType
        get() = when (val et = _eventType) {
            null -> throw IllegalStateException("Not yet started")
            ENTITY_REF if (expandEntities) -> TEXT
            else -> et
        }

    private var isSelfClosing: Boolean = false

    override val text: String
        get() = when {
            eventType.isTextElement -> getOutputString()
            else -> throw XmlException("The element is not text, it is: $eventType")
        }

    override val piTarget: String
        get() {
            check(eventType == PROCESSING_INSTRUCTION)
            return getOutputString().substringBefore(' ')
        }

    override val piData: String
        get() {
            check(eventType == PROCESSING_INSTRUCTION)
            return getOutputString().substringAfter(' ', "")
        }

    public fun isEmptyElementTag(): Boolean {
        if (_eventType != START_ELEMENT) exception(ILLEGAL_TYPE)
        return isSelfClosing
    }

    private var isUnresolvedEntity: Boolean = false

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
    private var entityName: String? = null

    public override val isKnownEntity: Boolean
        get() = when (_eventType) {
            ENTITY_REF -> !isUnresolvedEntity

            else -> throw IllegalStateException("isKnownEntity is only relevant for entities")
        }
    //endregion Entities

    //region Document declaration
    public override var version: String? = null
        @XmlUtilInternal
        private set

    public override var standalone: Boolean? = null
        @XmlUtilInternal
        private set

    override var encoding: String? = encoding
        @XmlUtilInternal
        private set

    public var docTypeName: String? = null
        private set

    public var docTypePublicId: String? = null
        private set

    public var docTypeSystemId: String? = null
        private set
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
    override val extLocationInfo: XmlReader.LocationInfo
        get() = inputBuffer.locationInfo

    public fun getLineNumber(): Int {
        return inputBuffer.line
    }

    public fun getColumnNumber(): Int {
        return inputBuffer.column
    }

    //endregion Location info

    //region Attributes
    override var attributeCount: Int = 0
        private set

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


    private fun clearAttributes() {
        val oldSize = attributeCount
        if (oldSize > 0) {
            attrData.fill(null, 0, oldSize * 4)
        }
        attributeCount = 0
    }

    private fun shrinkAttributeBuffer(newSize: Int) {
        attrData.fill(null, newSize * 4, attributeCount * 4)
        attributeCount = newSize
    }

    private fun ensureAttributeBufferCapacity(required: Int) {
        val requiredSize = required * 4
        val oldData = attrData
        if (oldData.size >= requiredSize) return

        attrData = oldData.copyOf(requiredSize + 16)
    }

    private fun addUnresolvedAttribute(attrPrefix: String?, attrLocalName: String, attrValue: String) {
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

    private fun copyAttributeNotNS(fromIdx: Int, toIdx: Int) {
        attrData.copyInto(attrData, toIdx * 4 + 1, fromIdx * 4 + 1, fromIdx * 4 + 4)
    }

    private fun attribute(index: Int): AttributeDelegate = AttributeDelegate(index)

    private var AttributeDelegate.namespace: String?
        get() {
            if (index >= attributeCount) throw IndexOutOfBoundsException()
            return attrData[index * 4]
        }
        set(value) {
            attrData[index * 4] = value
        }

    private var AttributeDelegate.prefix: String?
        get() {
            if (index >= attributeCount) throw IndexOutOfBoundsException()
            return attrData[index * 4 + 1]
        }
        set(value) {
            attrData[index * 4 + 1] = value
        }

    private var AttributeDelegate.localName: String?
        get() {
            if (index >= attributeCount) throw IndexOutOfBoundsException()
            return attrData[index * 4 + 2]
        }
        set(value) {
            attrData[index * 4 + 2] = value
        }

    private var AttributeDelegate.value: String?
        get() {
            if (index >= attributeCount) throw IndexOutOfBoundsException()
            return attrData[index * 4 + 3]
        }
        set(value) {
            attrData[index * 4 + 3] = value
        }

    @JvmInline
    private value class AttributeDelegate(public val index: Int)


    private class AttributesCollection

    //endregion Attributes

    //region Elements
    private var elementStack: ElementStack = ElementStack()


    private var elementData: Array<String?> = arrayOfNulls(48)

    private fun element(idx: Int) = ElementDelegate(idx)

    private var ElementDelegate.namespace: String?
        get() {
            if (index >= depth) throw IndexOutOfBoundsException()
            return elementData[index * 3]
        }
        set(value) {
            elementData[index * 3] = value
        }

    private var ElementDelegate.prefix: String?
        get() {
            if (index >= depth) throw IndexOutOfBoundsException()
            return elementData[index * 3 + 1]
        }
        set(value) {
            elementData[index * 3 + 1] = value
        }

    private var ElementDelegate.localName: String?
        get() {
            if (index >= depth) throw IndexOutOfBoundsException()
            return elementData[index * 3 + 2]
        }
        set(value) {
            elementData[index * 3 + 2] = value
        }

    @JvmInline
    private value class ElementDelegate(public val index: Int)

    private inner class ElementStack {

        public operator fun get(idx: Int): ElementDelegate = element(idx)

        public fun ensureCapacity(required: Int) {
            val requiredCapacity = required * 3 // three slots per element
            if (elementData.size >= requiredCapacity) return

            elementData = elementData.copyOf(requiredCapacity + 12)
        }

    }
    //endregion Elements

    //region State
    private var state: State = State.BEFORE_START

    override val isStarted: Boolean
        get() = state != State.BEFORE_START

    @XmlUtilInternal
    private enum class State {
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

    private fun pushCodePoint(c: Int) {
        if (c < 0) error("UNEXPECTED EOF")

        if (c > 0xffff) { // This comparison works as surrogates are in the 0xd800-0xdfff range
            // write high Unicode value as surrogate pair
            val offset = c - 0x010000

            val high = ((offset ushr 10) + 0xd800).toChar() // high surrogate
            val low = ((offset and 0x3ff) + 0xdc00).toChar() // low surrogate
            inputBuffer.addToCopySequence(high)
            inputBuffer.addToCopySequence(low)
        } else {
            inputBuffer.addToCopySequence(c.toChar())
        }
    }

    internal inline fun pushCopySequence(block: () -> Unit) {
        val x = inputBuffer.createCopySequence(block)
        setOutputBuffer(x)
    }

    //endregion

    //region Error handling

    private var error: String? = null

    private fun error(desc: String) {
        if (relaxed) {
            if (error == null) error = "ERR: $desc"
        } else exception(desc)
    }

    private fun exception(desc: String): Nothing {
        throw XmlException(
            when {
                desc.length < 100 -> desc
                else -> "${desc.take(100)}\n"
            },
            this
        )
    }

    private fun pushErrorComment(): Boolean = when (val e = error){
        null -> false
        else -> {
            setOutputBuffer("At: $extLocationInfo - $e")

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
     * Skip reading whitespace
     */
    private fun skipWS() {
        inputBuffer.skipWS()
    }

    private fun pushAttributeValue(delimiter: Char) {
        while (true) {
            when (inputBuffer.peekChar()) {
                '&' -> pushEntity(true)
                '\t', '\n' -> {
                    inputBuffer.pauseCopySequence()
                    inputBuffer.addToCopySequence(' ')
                    inputBuffer.markPeekedAsRead()
                    inputBuffer.resumeCopySequence()
                }

                '\r' -> throw AssertionError("Carriage returns should have been normalized out here")

                delimiter -> return
                else -> inputBuffer.markPeekedAsRead()
            }
        }
    }

    /** Push attribute delimited by whitespace. Only used in relaxed mode */
    private fun pushWSDelimAttrValue() {
        while (true) {
            when (inputBuffer.peekChar()) {
                '&' -> pushEntity(true)
                '>', '\t', '\n', '\r', ' ' -> return
                '/' if inputBuffer.peek(1, '>') -> return
                else -> inputBuffer.markPeekedAsRead()
            }
        }
    }

    /**
     * Read the next character and assert it is the expected character.
     */
    private fun readAssert(c: Char) {
        val a = inputBuffer.read()
        if (a != c.code) error("expected: '$c' actual: '${a.toChar()}'")
    }

    private fun readAssert(s: String, errorMessage: (Char) -> String ) {
        for (c in s) {
            val d = inputBuffer.read()
            if (c.code != d) error(errorMessage(c))
        }
    }

    private fun readAssert(s: String): Unit = readAssert(s) { c ->
        "Found unexpected character '$c' while parsing '$s' at ${inputBuffer.locationInfo}"
    }

    //endregion

    /** Sets name and attributes  */
    private fun parseStartTag(xmldecl: Boolean) {
        val prefix: String?
        val localName: String
        resetOutputBuffer()
        if (xmldecl) {
            prefix = null
            readAssert("xml")
            val next = inputBuffer.peekChar()
            require(! next.isNameChar()) { "XML declarations must be prefixed with '<?xml' and must be followed by a non-name-char" }
            localName = "xml"
        } else {
            val s = parseNCName().toString()
            if (inputBuffer.peek(':')) {
                prefix = s
                inputBuffer.markPeekedAsRead()
                localName = parseNCName().toString()
            } else {
                prefix = null
                localName = s
            }
        }
        clearAttributes()
        while (true) {
            skipWS()
            when (val c = inputBuffer.peek()) {
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
                    if (isXmlWhitespace(inputBuffer.peek().toChar())) {
                        error("ERR: Whitespace between empty content tag closing elements")
                        while (isXmlWhitespace(inputBuffer.peek().toChar())) {
                            val _ = inputBuffer.read()
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
                        val s = parseNCName().toString()
                        val aLocalName: String?
                        val aPrefix: String?
                        if (inputBuffer.peek(':')) {
                            aPrefix = s
                            inputBuffer.markPeekedAsRead()
                            aLocalName = parseNCName().toString()
                        } else {
                            aPrefix = null
                            aLocalName = s
                        }


                        if (aLocalName.isEmpty()) {
                            error("attr name expected")
                            break
                        }
                        skipWS()
                        if (inputBuffer.peek() != '='.code) {
                            val fullname = fullname(aPrefix, aLocalName)
                            error("Attr.value missing in $fullname '='. Found: ${inputBuffer.peek().toChar()}")

                            addUnresolvedAttribute(aPrefix, aLocalName, fullname)
                        } else {
                            readAssert('=')
                            skipWS()
                            val value: String
                            when (val delimiter = inputBuffer.peekChar()) {
                                '\'', '"' -> {
                                    inputBuffer.markPeekedAsRead()
                                    // This is an attribute, we don't care about whitespace content
                                    value = inputBuffer.createCopySequence { pushAttributeValue(delimiter) }.toString()
                                    readAssert(delimiter)
                                }

                                else -> {
                                    error("attr value delimiter missing!")
                                    value = inputBuffer.createCopySequence { pushWSDelimAttrValue() }.toString()
                                }
                            }

                            addUnresolvedAttribute(aPrefix, aLocalName, value)
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

    private fun parseCData() {
        readAssert("<![CDATA[")

        pushCopySequence {
            inputBuffer.addDelimitedToCopySequence("]]>")
        }
        return
    }

    private fun parseSystemLiteral(): CharSequence {
        // literals can be any character except the delimiter.
        return when (val r = inputBuffer.readChar()) {
            '\'' -> inputBuffer.createCopySequence { inputBuffer.addDelimitedToCopySequence('\'') }
            '"' -> inputBuffer.createCopySequence { inputBuffer.addDelimitedToCopySequence('"') }
            else -> {
                error("Quoted text must start with a single or double quote. Found: $r")
                ""
            }
        }
    }

    private fun parsePublicId(): CharSequence {
        val delim = inputBuffer.readChar()
        if (delim != '\'' && delim != '"') error("Invalid delimiter for public id: '$delim'. Expected: ' or \"")

        val r = inputBuffer.createCopySequence {
            var c = inputBuffer.peekChar()
            while (c != delim) {
                if (c.code>=PUBID_CHAR.size || !PUBID_CHAR[c.code]) {
                    error("Invalid character in public id: '${c}'")
                }
                inputBuffer.markPeekedAsRead()
                c = inputBuffer.peekChar()
            }
        }
        inputBuffer.markPeekedAsRead() // delimiter
        return r
    }

    private fun parseDoctype() {
        readAssert("<!DOCTYPE")

        inputBuffer.readWS()

        docTypeName = parseName().toString()

        skipWS()

        when (val p = inputBuffer.peek()) {
            '>'.code -> return

            'S'.code -> {
                readAssert("SYSTEM")
                inputBuffer.readWS()
                docTypeSystemId = parseSystemLiteral().toString()
                skipWS()
            }

            'P'.code -> {
                readAssert("PUBLIC")
                inputBuffer.readWS()
                docTypePublicId = parsePublicId().toString()
                inputBuffer.readWS()
                docTypeSystemId = parseSystemLiteral().toString()
                skipWS()
            }

            '['.code -> Unit // internal subset

            else -> error("Unexpected content in document type declaration: ${p.toChar()}")
        }

        if (inputBuffer.tryRead('[')) {
            val docTypeParser = DoctypeParser(inputBuffer, version == "1.1")
            docTypeParser.parse()
            for ((name, decl) in docTypeParser.generalEntities) {
                recordEntity(name, decl)
            }

            readAssert(']')
            skipWS()
        }

        readAssert('>')
    }

    private data class PEReference(val name: String, val value: String)

    /**
     * Add the entity to the output sequence.
     * result: if the setName parameter is set,
     * the name of the entity is stored in "name"
     */
    private fun pushEntity(expandEntities: Boolean = this.expandEntities) {
        inputBuffer.pauseCopySequence()
        readAssert('&')

        when {
            inputBuffer.peek('#') -> pushCharEntity()
            else -> pushRefEntity(expandEntities)
        }
        inputBuffer.resumeCopySequence()
    }

    private fun resolveEntity(entityName: String): XmlEntity? {
        return entityMap[entityName]
    }

    private fun recordEntity(entityName: String, value: XmlEntity) {
        entityMap[entityName] = value
    }

    private fun Char.isNameChar() = when {
        version == "1.1" -> isNameChar11(this)
        else -> isNameChar10(this)
    }

    private fun pushNCName() {
        if (! isNameStartChar(inputBuffer.readChar())) {
            error("NCName must start with a letter or underscore: '${inputBuffer.peekChar()}'")
        }

        var c = inputBuffer.peek()
        while (c >= 0 && c != ':'.code && c.toChar().isNameChar()) {
            inputBuffer.skip(1) // no newlines in names
            c = inputBuffer.peek()
        }
    }

    private fun pushName() {
        if (! isNameStartChar(inputBuffer.readChar())) {
            error("NCName must start with a letter or underscore: '${inputBuffer.peekChar()}'")
        }

        var c = inputBuffer.peek()
        while (c >= 0 && c.toChar().isNameChar()) {
            inputBuffer.skip(1)
            c = inputBuffer.peek()
        }
    }

    private fun parseNCName(): CharSequence {
        return inputBuffer.createCopySequence { pushNCName() }
    }

    private fun parseName(): CharSequence {
        val startBuffer = inputBuffer.offset

        inputBuffer.peekChar().let { first ->
            if (!isNameStartChar(first)) {
                throw XmlException("Entity reference does not start with name char &${getOutputString()}${first}", inputBuffer.locationInfo)
            }
        }

        var offset = 1
        while (inputBuffer.peek(offset).let { c -> c >= 0 /*&& c != ':'.code*/ && c.toChar().isNameChar() }) {
            offset += 1
        }

        val code = inputBuffer.readSubRange(startBuffer, startBuffer + offset).toString()
        inputBuffer.skip(offset)

        return code
    }

    private fun pushRefEntity(expandEntities: Boolean) {
        val code = parseName().toString()
        readAssert(';')

        if (_eventType == ENTITY_REF) {
            entityName = code
        }

        val result = resolveEntity(code)
        isUnresolvedEntity = result == null
        when (result) {
            null -> {
                if (expandEntities) exception("Unknown entity \"&$code;\" in entity expanding mode")
            }

            else if result.isSimple -> inputBuffer.addToCopySequence(result.simpleValue)

            else -> {
                val b = (inputBuffer as? InjectingInputBuffer) ?: InjectingInputBuffer(inputBuffer).also { inputBuffer = it }

//                val injectionText = result.resolveEmbeddedEntities(entityMap)

                b.inject(code, result.replacementValue, result.location)
            }
        }
    }

    private fun pushCharEntity() {
        readAssert('#') // #

        var isHex: Boolean
        var current: Int

        when (val first = inputBuffer.readChar()) {
            'x' -> {
                isHex = true
                current = 0
            }

            in '0'..'9' -> {
                isHex = false
                current = addDigitToCodePoint(first, false, 0)
            }

            else -> {
                error("Unexpected character in character entity: '$first'")
                inputBuffer.addToCopySequence("&#")
                inputBuffer.addToCopySequence(first)
                inputBuffer.pauseCopySequence() // Peeked elements don't need pushing
                return
            }
        }

        while (true) {
            when (val char = inputBuffer.readChar()) {
                ';' -> break

                in '0'..'9' -> current = addDigitToCodePoint(char, isHex, current)

                in 'a'..'f' if isHex -> current = addDigitToCodePoint(char, true, current)

                in 'A'..'F' if isHex -> current = addDigitToCodePoint(char, true, current)

                else -> {
                    error("Unexpected character in character entity: '$char'")
                    inputBuffer.addToCopySequence('&')
                    inputBuffer.resumeCopySequence() // Peeked elements don't need pushing
                    return
                }
            }
        }

        inputBuffer.addCodepointToCopySequence(current)
        return
    }


    private fun pushNonWSText(delimiter: Char, expandEntities: Boolean) {
        _isWhitespace = false
        while (true) {
            val c = inputBuffer.peekChar()
            when (c) {
                delimiter -> return
                '&' -> when {
                    expandEntities -> pushEntity(expandEntities)
                    else -> return
                }

                else -> inputBuffer.markPeekedAsRead()
            }
        }
    }


    /**
     * Push the text until the [delimiter] to the output buffer.
     */
    private fun pushMaybeWSText(delimiter: Char) {
        _isWhitespace = true

        var c = inputBuffer.peek()
        while (c >= 0) {
            when {
                isXmlWhitespace(c.toChar()) -> {
                    inputBuffer.markPeekedAsRead()
                }

                c == delimiter.code -> return

                else -> return pushNonWSText(delimiter, expandEntities)
            }
            c = inputBuffer.peek()
        }
    }


    private fun parsePI() {
        readAssert("<?")
        val s = inputBuffer.createCopySequence {
            pushName()
            inputBuffer.addDelimitedToCopySequence("?>")
        }
        // first check that we end with a whitespace or end of content, otherwise we cannot have a 3 letter name
        val l = s.length
        if ((l == 3 || (l >= 4 && isXmlWhitespace(s[3]))) &&
            s[0].let { it == 'x' || it == 'X' } &&
            s[1].let { it == 'm' || it == 'M' } &&
            s[2].let { it == 'l' || it == 'L'}) {

            error("Processing instructions must not have '[xX][mM][lL]' as name")
        }
        setOutputBuffer(s)
    }

    private fun parseUnexpectedOrWS(eventType: EventType) {
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
                pushCopySequence { pushMaybeWSText('<') }
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
                pushCopySequence { pushEntity() }
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
        when (val b = inputBuffer) { //unwrap the injection wrapper if possible
            is InjectingInputBuffer -> {
                if (! b.isInjecting) inputBuffer = b.base
            }
        }
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


    private fun nextImplDocStart() {
        val eventType = peekType()
        if (eventType == START_DOCUMENT) {
            readAssert("<?")
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

            DOCDECL -> parseDoctype()

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

        if (pushErrorComment()) return

        val lastEvent = _eventType
        val eventType = peekType()
        _eventType = eventType
        when (eventType) {

            COMMENT -> parseComment()

            ENTITY_REF if (expandEntities) -> {
                pushCopySequence { pushNonWSText('<', true) }
                if (getOutputString().isEmpty()) return nextImplBody() //allow for tags in entity
            }
            ENTITY_REF -> pushCopySequence { pushEntity() }

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
                pushCopySequence { pushNonWSText('<', expandEntities) }
            } else {
//                pushMaybeWSTextXX('<')
                pushCopySequence { pushMaybeWSText('<') }
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
        if (pushErrorComment()) return

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

        pushCopySequence {
            inputBuffer.addDelimitedToCopySequence("--")
            if (inputBuffer.readChar() != '>') {
                error("XML Comments may not contain inner --, or be terminated by '--->'")
            }
        }

        return
    }

    private fun parseEndTag() {
        if (depth == 0) {
            error("element stack empty")
            _eventType = COMMENT
            return
        }

        readAssert("</")

        resetOutputBuffer()
        val spIdx = depth - 1
        val expectedPrefix = elementStack[spIdx].prefix //?: exception("Missing prefix")
        val expectedLocalName = elementStack[spIdx].localName ?: exception("Missing localname")

        // fast path implementation that just verifies the tags
        // (rather than parsing them directly without that knowledge of expectation)
        if (expectedPrefix != null) {
            readAssert(expectedPrefix) { "Expected prefix '$expectedPrefix' for closing tag" }
            readAssert(':')
        }
        readAssert(expectedLocalName) { "Expect local part '$expectedLocalName' for closing tag" }
        skipWS()
        readAssert('>')
    }

    private fun peekType(): EventType {
        return when (inputBuffer.peek()) {
            -1 -> END_DOCUMENT
            '&'.code -> ENTITY_REF
            '<'.code -> when (inputBuffer.peek(1)) {
                '/'.code -> END_ELEMENT
                '?'.code -> when {
                    // order backwards to ensure
                    inputBuffer.peek(2, "xml") && !isNameCodepoint(inputBuffer.peek(5)) ->
                        START_DOCUMENT

                    else -> PROCESSING_INSTRUCTION
                }

                '!'.code -> when (inputBuffer.peek(2)) {
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


    /** Target buffer for storing incoming text (including aggregated resolved entities)  */
    private var outputBuf: CharSequence? = null

    private fun getOutputString(): String {
        return when (val s = outputBuf) {
            null -> throw XmlException("Missing text when attempting to retrieve it", inputBuffer.locationInfo)

            is String -> s

            else -> s.toString().also { t ->
                outputBuf = t
            }
        }
    }

    /**
     * Empty the output buffer.
     */
    private fun resetOutputBuffer() {
        outputBuf = null
    }

    /**
     * Set the current output buffer to the given output
     */
    private fun setOutputBuffer(output: CharSequence) {
        ifAssertions { assert(outputBuf == null) { "Output buffer already set" } }
        outputBuf = output
    }

    override fun toString(): String {
        val et = this._eventType ?: return ("<!--Parsing not started yet-->")

        return buildString {
            append("KtXmlReader [")
            append(et.name)
            append(' ')
            when {
                et == START_ELEMENT || et == END_ELEMENT -> {
                    if (isSelfClosing) append("(empty) ")

                    append('<')
                    if (et == END_ELEMENT) append('/')

                    if (elementStack[depth - 1].prefix != null) append("{$namespaceURI}$prefix:")
                    append(name)

                    for (x in 0 until attributeCount) {
                        append(' ')
                        val a = attribute(x)
                        if (a.namespace != null) {
                            append('{').append(a.namespace).append('}').append(a.prefix).append(':')
                        }
                        append("${a.localName}='${a.value}'")
                    }

                    append('>')
                }

                et == IGNORABLE_WHITESPACE -> {}

                et != TEXT -> append(text)

                _isWhitespace -> append(
                    "(whitespace)"
                )

                else -> { // nonwhitespace text
                    var textCpy = text
                    if (textCpy.length > 16) textCpy = textCpy.take(16) + "..."
                    append(textCpy)
                }
            }
            if (inputBuffer.offset >= 0) {
                append(inputBuffer.locationInfo).append (" in ")
            }
            append(inputBuffer.toString())
            append(']')
        }
    }

    private companion object {
        const val PROCESS_NAMESPACES = true

        const val UNEXPECTED_EOF = "Unexpected EOF"
        const val ILLEGAL_TYPE = "Wrong event type"

        val PUBID_CHAR = BooleanArray(127).also {
            it[0x20] = true
            it[0xD] = true
            it[0xA] = true
            for (c in 'A'..'Z') it[c.code] = true
            for (c in 'a'..'z') it[c.code] = true
            for (c in '0'..'9') it[c.code] = true
            for (c in "-'()+,./:=?;!*#@\$_%") it[c.code] = true
        }

        @JvmStatic
        private fun fullname(prefix: String?, localName: String): String = when (prefix) {
            null -> localName
            else -> "$prefix:$localName"
        }

    }

}
