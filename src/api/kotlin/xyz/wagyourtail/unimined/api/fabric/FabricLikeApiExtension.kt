package xyz.wagyourtail.unimined.api.fabric

import xyz.wagyourtail.unimined.util.stream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

abstract class FabricLikeApiExtension(val name: String) {
    fun module(moduleName: String, version: String): String {
        val url = URI(getUrl(version))
        url.stream().use {
            val dbf = DocumentBuilderFactory.newInstance()
            val db = dbf.newDocumentBuilder()
            val doc = db.parse(it)
            val elements = doc.getElementsByTagName("dependency")
            for (i in 0 until elements.length) {
                val element = elements.item(i)
                var correct = false
                var vers: String? = null
                for (j in 0 until element.childNodes.length) {
                    val child = element.childNodes.item(j)
                    if (child.nodeName == "artifactId" && child.textContent == moduleName) {
                        correct = true
                    }
                    if (child.nodeName == "version") {
                        vers = child.textContent
                    }
                }
                if (correct) {
                    return getArtifactName(moduleName, vers)
                }
            }
        }
        throw IllegalStateException("Could not find module $moduleName in $name $version")
    }

    abstract fun getUrl(version: String): String
    abstract fun getArtifactName(moduleName: String, version: String?): String
}
