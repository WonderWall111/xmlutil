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
package nl.adaptivity.xmlutil.core.impl.dom

import nl.adaptivity.xmlutil.dom.PlatformProcessingInstruction
import nl.adaptivity.xmlutil.dom2.Node
import nl.adaptivity.xmlutil.dom2.ProcessingInstruction
import org.w3c.dom.NamedNodeMap

internal class ProcessingInstructionImpl(delegate: PlatformProcessingInstruction) :
    AbstractNodeImpl<PlatformProcessingInstruction>(delegate), ProcessingInstruction {
    override fun getTarget(): String = delegate.target

    override fun getData(): String = delegate.data

    override fun setData(data: String) {
        delegate.data = data
    }

    override fun appendChild(node: Node): Nothing {
        throw UnsupportedOperationException("No children in processing instruction")
    }

    override fun replaceChild(newChild: Node, oldChild: Node): Nothing {
        throw UnsupportedOperationException("No children in processing instruction")
    }

    override fun removeChild(node: Node): Nothing {
        throw UnsupportedOperationException("No children in processing instruction")
    }

    override fun getAttributes(): NamedNodeMap? {
        TODO("not implemented")
    }
}
