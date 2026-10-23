package dev.multiclone.engine

import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.chunk.xml.ResXmlNodeTree

internal const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
internal fun ResXmlElement.android(name: String) = searchAttribute(ANDROID_NS, name)
internal fun ResXmlElement.text(name: String) = android(name)?.valueAsString
internal fun ResXmlNodeTree.elements(): List<ResXmlElement> = buildList {
    val nodes = recursive()
    while (nodes.hasNext()) (nodes.next() as? ResXmlElement)?.let { add(it) }
}
internal fun fullClass(originalPackage: String, value: String): String = when {
    value.startsWith('.') -> originalPackage + value
    '.' !in value -> "$originalPackage.$value"
    else -> value
}
