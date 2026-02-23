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

package nl.adaptivity.xmlutil.serialization

import kotlinx.serialization.SerializationException
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlUtilInternal

public open class XmlSerialException(
    message: String,
    extLocationInfo: XmlReader.LocationInfo?,
    cause: Throwable? = null
) : SerializationException(message, cause) {
    public var extLocationInfo: XmlReader.LocationInfo? = extLocationInfo
        private set

    public constructor(message: String, cause: Throwable? = null) : this(message, null, cause)

    @XmlUtilInternal
    public fun setFileLocation(fileName: String) {
        val locationInfo = extLocationInfo?.withFileName(fileName) ?: FileNameLocationInfo(fileName)
        if (locationInfo !== extLocationInfo) extLocationInfo = locationInfo
    }

}


@XmlUtilInternal
public fun <E: XmlSerialException> E.withFileName(fileName: String): E {
    setFileLocation(fileName)
    return this
}

private class FileNameLocationInfo(val fileName: String): XmlReader.LocationInfo {
    override fun toString(): String {
        return "file $fileName@<unknown>"
    }

    override fun withFileName(fileName: String): XmlReader.LocationInfo = when {
        fileName == this.fileName -> this
        else -> FileNameLocationInfo(fileName)
    }
}

public class XmlParsingException(
    extLocationInfo: XmlReader.LocationInfo?,
    message: String,
    cause: Exception? = null
) : XmlSerialException("Invalid XML value at position: $extLocationInfo: $message", extLocationInfo, cause) {
    public constructor(locationInfo: String?, message: String, cause: Exception? = null) :
            this(locationInfo?.let(XmlReader::StringLocationInfo), message, cause)
}

public class UnknownXmlFieldException private constructor(
    message: String,
    extLocationInfo: XmlReader.LocationInfo?,
    cause: Throwable? = null
) : XmlSerialException(
    message,
    extLocationInfo,
    cause
) {

    public constructor(
        xmlName: String,
        extLocationInfo: XmlReader.LocationInfo?,
        candidates: Collection<Any> = emptyList()
    ) : this(
        "Could not find a field for name $xmlName${candidateString(candidates)}${extLocationInfo?.let { " at position $it" } ?: ""}",
        extLocationInfo,
        null
    )

    public constructor(
        locationInfo: String?,
        xmlName: String,
        candidates: Collection<Any> = emptyList()
    ) : this(xmlName, locationInfo?.let(XmlReader::StringLocationInfo), candidates)

}

private fun candidateString(candidates: Iterable<Any>) =
    when (candidates.iterator().hasNext()) {
        true -> candidates.joinToString(prefix = "\n  candidates: ") {
            when (it) {
                is PolyInfo -> "${it.tagName} (${it.descriptor.outputKind})"
                else -> it.toString()
            }
        }

        else -> ""
    }
