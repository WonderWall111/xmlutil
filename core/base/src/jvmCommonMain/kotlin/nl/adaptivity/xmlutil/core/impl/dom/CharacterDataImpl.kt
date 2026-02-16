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

package nl.adaptivity.xmlutil.core.impl.dom

import nl.adaptivity.xmlutil.core.impl.idom.ICharacterData
import nl.adaptivity.xmlutil.core.impl.idom.INode
import nl.adaptivity.xmlutil.dom.PlatformNode
import nl.adaptivity.xmlutil.dom2.Node
import org.w3c.dom.CharacterData

internal abstract class CharacterDataImpl<N : CharacterData>(delegate: N) : NodeImpl<N>(delegate), ICharacterData {
    override fun getData(): String = delegate.data

    override fun setData(data: String?) {
        delegate.data = data
    }

    override fun getLength(): Int = delegate.length

    override fun substringData(offset: Int, count: Int): String =
        delegate.substringData(offset, count)

    override fun appendData(data: String) {
        delegate.appendData(data)
    }

    override fun insertData(offset: Int, data: String) {
        delegate.insertData(offset, data)
    }

    override fun deleteData(offset: Int, count: Int) {
        delegate.deleteData(offset, count)
    }

    override fun replaceData(offset: Int, count: Int, data: String) {
        delegate.replaceData(offset, count, data)
    }

    override fun getFirstChild(): Nothing? = null

    override fun getLastChild(): Nothing? = null

    @IgnorableReturnValue
    override fun appendChild(node: Node): Nothing =
        throw UnsupportedOperationException("No children in character data")

    @IgnorableReturnValue
    override fun appendChild(node: INode): Nothing =
        throw UnsupportedOperationException("No children in character data")

    @IgnorableReturnValue
    override fun appendChild(newChild: PlatformNode): Nothing =
        throw UnsupportedOperationException("No children in character data")

    @IgnorableReturnValue
    override fun replaceChild(newChild: Node, oldChild: Node): Nothing =
        throw UnsupportedOperationException("No children in character data")

    @IgnorableReturnValue
    override fun replaceChild(newChild: INode, oldChild: INode): Nothing =
        throw UnsupportedOperationException("No children in character data")

    @IgnorableReturnValue
    override fun replaceChild(newChild: PlatformNode, oldChild: PlatformNode): Nothing =
        throw UnsupportedOperationException("No children in character data")

    @IgnorableReturnValue
    override fun removeChild(node: Node): Nothing =
        throw UnsupportedOperationException("No children in character data")

    @IgnorableReturnValue
    override fun removeChild(node: INode): Nothing =
        throw UnsupportedOperationException("No children in character data")

    @IgnorableReturnValue
    override fun removeChild(oldChild: PlatformNode): Nothing =
        throw UnsupportedOperationException("No children in character data")
}
