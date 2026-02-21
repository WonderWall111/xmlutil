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

import nl.adaptivity.xmlutil.core.impl.idom.*
import nl.adaptivity.xmlutil.dom.*
import nl.adaptivity.xmlutil.dom2.Attr
import nl.adaptivity.xmlutil.dom2.Node
import nl.adaptivity.xmlutil.dom2.NodeType
import org.w3c.dom.Text
import org.w3c.dom.UserDataHandler

internal abstract class NodeImpl<N : PlatformNode>(delegate: N) : INode {
    @Suppress("UNCHECKED_CAST")
    override val delegate: N = delegate.unWrap() as N

    final override fun getOwnerDocument(): IDocument = delegate.ownerDocument.wrap()

    final override fun getParentNode(): INode? = delegate.parentNode?.wrap()

    override fun getFirstChild(): INode? = delegate.firstChild?.wrap()

    override fun getLastChild(): INode? = delegate.lastChild?.wrap()

    final override fun getPreviousSibling(): INode? = delegate.previousSibling?.wrap()

    final override fun getNextSibling(): INode? = delegate.nextSibling?.wrap()

    final override fun getNodeName(): String = delegate.nodeName
    final override fun getNodetype(): NodeType = NodeType(delegate.nodeType)

    final override fun getNodeType(): Short = delegate.nodeType

    final override fun getTextContent(): String? = delegate.textContent

    final override fun setTextContent(value: String) {
        delegate.textContent = value
    }

    final override fun getChildNodes(): INodeList = WrappingNodeList(delegate.childNodes)

    final override fun getNodeValue(): String = delegate.nodeValue

    final override fun setNodeValue(nodeValue: String?) {
        delegate.nodeValue = nodeValue
    }

    final override fun insertBefore(newChild: PlatformNode?, refChild: PlatformNode?): INode {
        return delegate.insertBefore(newChild?.unWrap(), refChild?.unWrap()).wrap()
    }

    final override fun hasChildNodes(): Boolean = delegate.hasChildNodes()

    final override fun cloneNode(deep: Boolean): INode {
        return delegate.cloneNode(deep).wrap()
    }

    final override fun normalize() {
        delegate.normalize()
    }

    final override fun isSupported(feature: String?, version: String?): Boolean {
        return delegate.isSupported(feature, version)
    }

    final override fun getNamespaceURI(): String? = delegate.namespaceURI

    final override fun getPrefix(): String? = delegate.prefix

    final override fun setPrefix(prefix: String?) {
        delegate.prefix = prefix
    }

    override fun getLocalName(): String? = delegate.localName

    final override fun hasAttributes(): Boolean = delegate.hasAttributes()

    final override fun getBaseURI(): String = delegate.baseURI

    final override fun compareDocumentPosition(other: PlatformNode): Short {
        return delegate.compareDocumentPosition(other.unWrap())
    }

    final override fun isSameNode(other: PlatformNode?): Boolean = delegate.isSameNode(other?.unWrap())

    final override fun lookupPrefix(namespace: String): String? = delegate.lookupPrefix(namespace)

    final override fun isDefaultNamespace(namespaceURI: String): Boolean = delegate.isDefaultNamespace(namespaceURI)

    final override fun lookupNamespaceURI(prefix: String): String? = delegate.lookupNamespaceURI(prefix)

    final override fun isEqualNode(arg: PlatformNode): Boolean {
        return delegate.isEqualNode(arg.unWrap())
    }

    final override fun getFeature(feature: String, version: String?): Any? {
        return delegate.getFeature(feature, version)
    }

    final override fun setUserData(key: String, data: Any?, handler: UserDataHandler?): Any? {
        return delegate.setUserData(key, data, handler)
    }

    final override fun getUserData(key: String): Any? {
        return delegate.getUserData(key)
    }

    @IgnorableReturnValue
    override fun appendChild(node: INode): INode {
        return delegate.appendChild(node.unWrap()).wrap()
    }

    @IgnorableReturnValue
    override fun appendChild(newChild: PlatformNode): INode {
        return delegate.appendChild(newChild.unWrap()).wrap()
    }

    @IgnorableReturnValue
    override fun replaceChild(newChild: INode, oldChild: INode): INode {
        return delegate.replaceChild(newChild.unWrap(), oldChild.unWrap()).wrap()
    }

    @IgnorableReturnValue
    override fun replaceChild(newChild: PlatformNode, oldChild: PlatformNode): INode {
        return delegate.replaceChild(newChild.unWrap(), oldChild.unWrap()).wrap()
    }

    @IgnorableReturnValue
    override fun removeChild(node: INode): INode {
        return delegate.removeChild(node.unWrap()).wrap()
    }

    @IgnorableReturnValue
    override fun removeChild(oldChild: PlatformNode): INode {
        return delegate.removeChild(oldChild.unWrap()).wrap()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NodeImpl<*>

        return delegate == other.delegate
    }

    override fun hashCode(): Int {
        return delegate.hashCode()
    }

    override fun toString(): String = delegate.toString()

}

internal fun INode.unWrap(): PlatformNode = delegate

internal fun PlatformNode.unWrap(): PlatformNode = when (this) {
    is INode -> delegate
    else -> this
}

internal fun PlatformAttr.unWrap(): PlatformAttr = when (this) {
    is IAttr -> delegate as PlatformAttr
    else -> this
}

internal fun Attr.unWrap(): PlatformAttr = when (this) {
    is IAttr -> delegate as PlatformAttr
    else -> this
}

internal fun Node.unWrap(): PlatformNode = when (this) {
    is INode -> delegate
    else -> this.wrap() // has to be actually wrapped to "work"
}

internal fun PlatformNode.wrap(): INode = when (this) {
    is INode -> this
    is PlatformAttr -> AttrImpl(this)
    is PlatformCDATASection -> CDATASectionImpl(this)
    is PlatformComment -> CommentImpl(this)
    is PlatformDocument -> DocumentImpl(this)
    is PlatformDocumentFragment -> DocumentFragmentImpl(this)
    is PlatformDocumentType -> DocumentTypeImpl(this)
    is PlatformElement -> ElementImpl(this)
    is PlatformProcessingInstruction -> ProcessingInstructionImpl(this)
    is Text -> TextImpl(this)
    else -> error("Node type ${NodeType(nodeType)} not supported")
}

internal fun Node.wrap(): INode = when (this) {
    is INode -> this
    else -> error("Node type ${getNodetype()} not supported")
}

internal fun PlatformDocument.wrap(): IDocument = when (this) {
    is IDocument -> this
    else -> DocumentImpl(this)
}

internal fun PlatformElement.wrap(): IElement = when (this) {
    is IElement -> this
    else -> ElementImpl(this)
}

internal fun Text.wrap(): IText = when (this) {
    is IText -> this
    else -> TextImpl(this)
}

internal fun PlatformDocumentType.wrap(): IDocumentType = when (this) {
    is IDocumentType -> this
    else -> DocumentTypeImpl(this)
}

internal fun PlatformAttr.wrap(): IAttr = when (this) {
    is IAttr -> this
    else -> AttrImpl(this)
}
