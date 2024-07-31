package xyz.wagyourtail.unimined.internal.mapping

import net.fabricmc.tinyremapper.IMappingProvider
import okio.BufferedSource
import okio.buffer
import okio.source
import okio.use
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import xyz.wagyourtail.unimined.api.UniminedExtension
import xyz.wagyourtail.unimined.api.mapping.MappingsConfig
import xyz.wagyourtail.unimined.api.mapping.dsl.MappingDSL
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.mapping.EnvType
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.mapping.formats.mcp.v3.MCPv3ClassesReader
import xyz.wagyourtail.unimined.mapping.formats.mcp.v3.MCPv3FieldReader
import xyz.wagyourtail.unimined.mapping.formats.mcp.v3.MCPv3MethodReader
import xyz.wagyourtail.unimined.mapping.formats.rgs.RetroguardReader
import xyz.wagyourtail.unimined.mapping.formats.srg.SrgReader
import xyz.wagyourtail.unimined.mapping.formats.umf.UMFReader
import xyz.wagyourtail.unimined.mapping.formats.umf.UMFWriter
import xyz.wagyourtail.unimined.mapping.jvms.four.three.three.MethodDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.three.two.FieldDescriptor
import xyz.wagyourtail.unimined.mapping.jvms.four.two.one.InternalName
import xyz.wagyourtail.unimined.mapping.propogator.Propagator
import xyz.wagyourtail.unimined.mapping.resolver.ContentProvider
import xyz.wagyourtail.unimined.mapping.tree.MemoryMappingTree
import xyz.wagyourtail.unimined.mapping.visitor.*
import xyz.wagyourtail.unimined.mapping.visitor.delegate.Delegator
import xyz.wagyourtail.unimined.mapping.visitor.delegate.delegator
import xyz.wagyourtail.unimined.mapping.visitor.fixes.renest
import xyz.wagyourtail.unimined.util.*
import java.io.File
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

class MappingsProvider(project: Project, minecraft: MinecraftConfig, subKey: String? = null) : MappingsConfig<MappingsProvider>(project, minecraft, subKey) {
    val unimined: UniminedExtension = project.unimined

    override fun createForPostProcess(key: String): MappingsProvider {
        return MappingsProvider(project, minecraft, key)
    }

    val mappings = project.configurations.detachedConfiguration()

    var stubMappings: MemoryMappingTree? = null

    var legacyFabricGenVersion by FinalizeOnRead(1)
    var ornitheGenVersion by FinalizeOnRead(1)

    var splitUnmapped by FinalizeOnRead(LazyMutable {
        minecraft.minecraftData.mcVersionCompare(minecraft.version, "1.3") <= 0
    })

    override var envType: EnvType by LazyMutable {
        minecraft.side
    }

    override val unmappedNs: Set<Namespace> by lazy {
        if (splitUnmapped && envType == EnvType.JOINED) {
            setOf(Namespace("clientOfficial"), Namespace("serverOfficial"))
        } else {
            setOf(Namespace("official"))
        }
    }

    override fun propogator(tree: MemoryMappingTree): MemoryMappingTree {
        // propagate
        val mappings = MemoryMappingTree()
        mappings.visitHeader(*tree.namespaces.map { it.name }.toTypedArray())

        if (splitUnmapped && envType == EnvType.JOINED) {
            Propagator(Namespace("clientOfficial"), tree, setOf(minecraft.minecraftData.minecraftClientFile.toPath())).propagate(tree.namespaces.toSet() - Namespace("serverOfficial"), mappings)
            Propagator(Namespace("serverOfficial"), tree, setOf(minecraft.minecraftData.minecraftServerFile.toPath())).propagate(tree.namespaces.toSet() - Namespace("clientOfficial"), mappings)
        } else {
            Propagator(Namespace("official"), tree, setOf(when (envType) {
                EnvType.JOINED -> minecraft.mergedOfficialMinecraftFile
                EnvType.CLIENT -> minecraft.minecraftData.minecraftClientFile
                EnvType.SERVER -> minecraft.minecraftData.minecraftServerFile
            }!!.toPath())).propagate(tree.namespaces.toSet() - Namespace("official"), mappings)
        }
        return mappings
    }

    fun legacyFabricRevisionTransform(mavenCoords: MavenCoords): MavenCoords {
        if (legacyFabricGenVersion < 2) {
            return mavenCoords
        }
        return MavenCoords("${mavenCoords.group}v${legacyFabricGenVersion}", mavenCoords.artifact, mavenCoords.version, mavenCoords.classifier, mavenCoords.extension)
    }

    fun ornitheGenRevisionTransform(mavenCoords: MavenCoords): MavenCoords {
        if (ornitheGenVersion < 2) {
            return mavenCoords
        }
        return MavenCoords(mavenCoords.group, "${mavenCoords.artifact}-gen$ornitheGenVersion", mavenCoords.version, mavenCoords.classifier, mavenCoords.extension)
    }


    override fun intermediary(key: String, action: MappingEntry.() -> Unit) {
        unimined.fabricMaven()
        addDependency(key, MappingEntry(contentOf(
            MavenCoords(
                "net.fabricmc",
                "intermediary",
                minecraft.version,
                "v2"
            )
        ), key).apply {
            provides("intermediary" to false)
            action()
        })
    }

    override fun calamus(key: String, action: MappingEntry.() -> Unit) {
        unimined.ornitheMaven()
        val environment = if (minecraft.minecraftData.mcVersionCompare(minecraft.version, "1.3") < 0) {
            when (envType) {
                EnvType.CLIENT -> "-client"
                EnvType.SERVER -> "-server"
                else -> throw IllegalStateException("Cannot use Calamus on Minecraft ${minecraft.version} with side COMBINED")
            }
        } else {
            ""
        }
        addDependency(key, MappingEntry(
            contentOf(ornitheGenRevisionTransform(
                MavenCoords(
                "net.ornithemc",
                "calamus-intermediary",
                minecraft.version + environment,
                "v2"
            )
            )),
            key
        ).apply {
            provides("calamus" to false)
            mapNamespace("intermediary", "calamus")
            action()
        })
    }

    
    override fun legacyIntermediary(key: String, action: MappingEntry.() -> Unit) {
        unimined.legacyFabricMaven()
        addDependency(key, MappingEntry(
            contentOf(legacyFabricRevisionTransform(
                MavenCoords(
                "net.legacyfabric",
                "intermediary",
                minecraft.version,
                "v2"
            )
            )),
            key
        ).apply {
            provides("legacyIntermediary" to false)
            mapNamespace("intermediary", "legacyIntermediary")
            action()
        })
    }

    
    override fun babricIntermediary(key: String, action: MappingEntry.() -> Unit) {
        unimined.glassLauncherMaven("babric")
        addDependency(key, MappingEntry(contentOf(MavenCoords("babric", "intermediary", minecraft.version, "v2")), key).apply {
            provides("babricIntermediary" to false)
            when (envType) {
                EnvType.CLIENT -> {
                    mapNamespace("client", "official")
                    mapNamespace("clientOfficial", "official")
                }
                EnvType.SERVER -> {
                    mapNamespace("server", "official")
                    mapNamespace("serverOfficial", "official")
                }
                EnvType.JOINED -> {
                    mapNamespace("client", "clientOfficial")
                    mapNamespace("server", "serverOfficial")
                    provides("serverOfficial" to false)
                    requires("clientOfficial")
                }
            }
            mapNamespace("intermediary", "babricIntermediary")
            action()
        })
    }

    
    override fun searge(version: String, key: String, action: MappingEntry.() -> Unit) {
        unimined.minecraftForgeMaven()
        val mappings = if (minecraft.minecraftData.mcVersionCompare(minecraft.version, "1.12.2") < 0) {
            MavenCoords("de.oceanlabs.mcp", "mcp", version, "srg", "zip")
        } else {
            MavenCoords("de.oceanlabs.mcp", "mcp_config", version, null,"zip")
        }
        if (minecraft.minecraftData.mcVersionCompare(minecraft.version, "1.16.5") > 0) {
            postProcessDependency(key, {
                mojmap()
                addDependency(key, MappingEntry(contentOf(mappings), "$key-$version").apply {
                    mapNamespace("obf" to "official")
                    requires("mojmap")
                    provides("srg" to false)
                })
            }) {
                mapNamespace("srg" to "searge")
                provides("searge" to false)
                insertInto.add {
                    it.delegator(object: Delegator() {
                        val searge = Namespace("searge")
                        val mojmap = Namespace("mojmap")

                        override fun visitClass(
                            delegate: MappingVisitor,
                            names: Map<Namespace, InternalName>
                        ): ClassVisitor? {
                            return if (mojmap in names) {
                                super.visitClass(delegate, names + (searge to names[mojmap]!!))
                            } else {
                                super.visitClass(delegate, names)
                            }
                        }
                    })
                }
                action()
            }
        } else {
            addDependency(key, MappingEntry(contentOf(mappings), "$key-$version").apply {
                provides("searge" to false)
                mapNamespace("source" to "official", "target" to "searge")
                action()
            })
        }
    }

    
    override fun mojmap(key: String, action: MappingEntry.() -> Unit) {
        val mappings = when (envType) {
            EnvType.CLIENT, EnvType.JOINED -> "client"
            EnvType.SERVER -> "server"
        }
        addDependency(key, MappingEntry(contentOf(
            MavenCoords(
                "net.minecraft",
                "$mappings-mappings",
                minecraft.version,
                null,
                "txt"
            )),
            key
        ).apply {
            mapNamespace("source" to "mojmap", "target" to "official")
            provides("mojmap" to true)
            action()
        })
    }

    
    override fun mcp(channel: String, version: String, key: String, action: MappingEntry.() -> Unit) {
        if (channel == "legacy") {
            unimined.wagYourMaven("releases")
        } else {
            unimined.minecraftForgeMaven()
        }
        if (envType == EnvType.JOINED && minecraft.minecraftData.mcVersionCompare(minecraft.version, "1.3") < 0) throw UnsupportedOperationException("MCP mappings are not supported in joined environments before 1.3")
        val mappings = "de.oceanlabs.mcp:mcp_${channel}:${version}@zip"
        addDependency(key, MappingEntry(contentOf(mappings), "$key-$channel-$version").apply {
            subEntry { _, format ->
                when (format.reader) {
                    is RetroguardReader, SrgReader -> {
                        mapNamespace("source" to "official", "target" to "searge")
                        provides("searge" to false)
                    }
                    is MCPv3ClassesReader, MCPv3FieldReader, MCPv3MethodReader -> {
                        provides("searge" to false, "mcp" to true)
                    }
                     else -> {
                         requires("searge")
                         provides("mcp" to true)
                     }
                }
            }
            action()
        })
    }

    
    override fun retroMCP(version: String, key: String, action: MappingEntry.() -> Unit) {
        unimined.mcphackersIvy()
        addDependency(key, MappingEntry(contentOf(MavenCoords("io.github.mcphackers", "mcp", version, "zip")), "$key-$version").apply {
            mapNamespace("named" to "retroMCP")
            if (splitUnmapped) {
                when (envType) {
                    EnvType.CLIENT -> {
                        mapNamespace("client", "official")
                        mapNamespace("clientOfficial", "official")
                    }

                    EnvType.SERVER -> {
                        mapNamespace("server", "official")
                        mapNamespace("serverOfficial", "official")
                    }

                    EnvType.JOINED -> {
                        mapNamespace("client", "clientOfficial")
                        mapNamespace("server", "serverOfficial")
                        requires("clientOfficial")
                        provides("serverOfficial" to false)
                    }
                }
            }
            provides("retroMCP" to true)
            action()
        })
    }

    
    override fun yarn(build: Int, key: String, action: MappingEntry.() -> Unit) {
        unimined.fabricMaven()
        val entry = MappingEntry(contentOf(
            MavenCoords(
            "net.fabricmc",
            "yarn",
            minecraft.version + "+build.$build",
            "v2"
        )), "$key-$build"
        ).apply {
            requires("intermediary")
            mapNamespace("named" to "yarn")
            provides("yarn" to true)
            action()
        }
        addDependency(key, entry)

        afterLoad.add {
            renest(entry.requires.name, *entry.provides.map { it.first.name }.toTypedArray())
        }
    }

    override fun yarnv1(build: Int, key: String, action: MappingEntry.() -> Unit) {
        TODO("Not yet implemented")
    }

    
    override fun feather(build: Int, key: String, action: MappingEntry.() -> Unit) {
        unimined.ornitheMaven()
        val beforeJoined = minecraft.minecraftData.mcVersionCompare(minecraft.version, "1.2.5") <= 0
        val vers = if (beforeJoined) {
            if (envType == EnvType.JOINED) throw UnsupportedOperationException("Feather mappings are not supported in joined environments before 1.2.5")
            "${minecraft.version}-${envType.name.lowercase()}+build.$build"
        } else {
            "${minecraft.version}+build.$build"
        }
        val entry = MappingEntry(
            contentOf(ornitheGenRevisionTransform(
                MavenCoords(
                    "net.ornithemc",
                    "feather",
                    vers,
                    "v2"
                ))), "$key-$build"
        ).apply {
            requires("calamus")
            provides("feather" to true)
            mapNamespace("intermediary" to "calamus", "named" to "feather")
            insertInto.add {
                it.delegator(object: Delegator() {
                    val calamus = Namespace("calamus")
                    val feather = Namespace("feather")

                    override fun visitClass(
                        delegate: MappingVisitor,
                        names: Map<Namespace, InternalName>
                    ): ClassVisitor? {
                        return if (feather in names) {
                            super.visitClass(
                                delegate,
                                names + (feather to InternalName.unchecked(
                                    names[feather]!!.toString().replace("__", "$")
                                ))
                            )
                        } else {
                            super.visitClass(delegate, names)
                        }
                    }

                })
            }
            action()
        }
        addDependency(key, entry)

        afterLoad.add {
            renest(entry.requires.name, *entry.provides.map { it.first.name }.toTypedArray())
        }
    }

    
    override fun legacyYarn(build: Int, key: String, action: MappingEntry.() -> Unit) {
        unimined.legacyFabricMaven()
        val entry = MappingEntry(
            contentOf(legacyFabricRevisionTransform(
                MavenCoords(
                "net.leagcyfabric",
                "yarn",
                "${minecraft.version}+build.$build",
                "v2"
            ))), "$key-$build"
        ).apply {
            requires("legacyIntermediary")
            provides("legacyYarn" to true)
            mapNamespace("intermediary" to "legacyIntermediary", "named" to "legacyYarn")
            action()
        }
        addDependency(key, entry)

        afterLoad.add {
            renest(entry.requires.name, *entry.provides.map { it.first.name }.toTypedArray())
        }
    }

    
    override fun barn(build: Int, key: String, action: MappingEntry.() -> Unit) {
        unimined.glassLauncherMaven("babric")
        val entry = MappingEntry(contentOf(MavenCoords(
            "babric",
            "barn",
            "${minecraft.version}+build.$build", "v2")
        ), "$key-$build").apply {
            requires("babricIntermediary")
            provides("barn" to true)
            mapNamespace("intermediary" to "babricIntermediary", "named" to "barn")
            action()
        }
        addDependency(key, entry)

        afterLoad.add {
            renest(entry.requires.name, *entry.provides.map { it.first.name }.toTypedArray())
        }
    }

    override fun biny(commitName: String, key: String, action: MappingEntry.() -> Unit) {
        unimined.glassLauncherMaven("releases")
        val entry = MappingEntry(contentOf(
            MavenCoords("net.glasslauncher", "biny", "${minecraft.version}+$commitName", "v2")
        ), "$key-$commitName").apply {
            requires("babricIntermediary")
            provides("biny" to true)
            mapNamespace("intermediary" to "babricIntermediary", "named" to "biny")
            action()
        }
        addDependency(key, entry)

        afterLoad.add {
            renest(entry.requires.name, *entry.provides.map { it.first.name }.toTypedArray())
        }
    }

    
    override fun quilt(build: Int, key: String, action: MappingEntry.() -> Unit) {
        unimined.quiltMaven()
        val entry = MappingEntry(contentOf(
            MavenCoords(
                "org.quiltmc",
                "quilt-mappings",
            "${minecraft.version}+build.$build",
                "intermediary-v2"
            )), "$key-$build"
        ).apply {
            requires("intermediary")
            provides("quilt" to true)
            action()
        }
        addDependency(key, entry)

        afterLoad.add {
            renest(entry.requires.name, *entry.provides.map { it.first.name }.toTypedArray())
        }
    }

    
    override fun forgeBuiltinMCP(version: String, key: String, action: MappingEntry.() -> Unit) {
        unimined.minecraftForgeMaven()
        addDependency(key, MappingEntry(contentOf(
            MavenCoords(
                "net.minecraftforge",
                "forge",
                "${minecraft.version}-$version",
                "src",
                "zip"
            )), "$key-$version"
        ).apply {
            subEntry {_, format ->
                when (format.reader) {
                    is SrgReader -> {
                        mapNamespace("source" to "official", "target" to "searge")
                        provides("searge" to false)
                    }
                    else -> {
                        requires("searge")
                        mapNamespace("mcp" to "forgeMCP")
                        provides("forgeMCP" to true)
                    }
                }
            }
            action()
        })
    }

    override fun parchment(
        mcVersion: String,
        version: String,
        checked: Boolean,
        key: String,
        action: MappingEntry.() -> Unit
    ) {
        unimined.parchmentMaven()
        addDependency(key, MappingEntry(contentOf(
            MavenCoords(
                "org.parchmentmc.data",
                "parchment-$mcVersion",
                version,
                if (checked) "checked" else null,
                "zip"
            )), "$key-$version"
        ).apply {
            requires("mojmap")
            mapNamespace("source" to "mojmap")
            provides("mojmap" to true)
            action()
        })
    }

    override fun spigotDev(mcVersion: String, key: String, action: MappingEntry.() -> Unit) {
        TODO("Not yet implemented")
    }
    
    override fun mapping(dependency: String, key: String, action: MappingEntry.() -> Unit) {
        val coords = MavenCoords(dependency)
        addDependency(key, MappingEntry(contentOf(coords), "$key-${coords.version}").apply {
            action()
        })
    }

    override fun mapping(dependency: File, key: String, action: MappingEntry.() -> Unit) {
        addDependency(key, MappingEntry(contentOf(dependency), key).apply {
            action()
        })
    }

    fun contentOf(coords: MavenCoords): ContentProvider {
        val dep = project.dependencies.create(coords.toString())
        mappings.dependencies.add(dep)
        return MappingContentProvider(dep, coords.extension)
    }

    fun contentOf(coords: String): ContentProvider {
        return contentOf(MavenCoords(coords))
    }

    fun contentOf(file: File): ContentProvider {
        return MappingContentProvider(project.dependencies.create(file), file.extension)
    }

    override fun hasStubs(): Boolean {
        return stubMappings != null
    }

    override suspend fun combinedNames(): String = buildString {
        append(super.combinedNames())
        if (hasStubs()) {
            append("-stubs-")
            append(buildString { stubMappings!!.accept(UMFWriter.write(::append)) }.getShortSha1())
        }
    }

    override fun stubs(vararg namespaces: String, apply: MappingDSL.() -> Unit) {
        if (finalized) {
            throw UnsupportedOperationException("Cannot add stub mappings after finalization")
        }
        if (stubMappings == null) {
            stubMappings = MemoryMappingTree()
            afterLoad.add {
                stubMappings!!.accept(this)
            }
        }
        MappingDSL(stubMappings!!).apply {
            namespace(*namespaces)
            apply()
        }
    }

    override suspend fun fromCache(key: String): MemoryMappingTree? {
        val mappingFile = if (hasStubs()) { unimined.getLocalCache().resolve("mappings") } else { minecraft.minecraftData.mcVersionFolder }.resolve("mappings-${key}.umf")
        if (!mappingFile.exists() || unimined.forceReload || project.gradle.startParameter.isRefreshDependencies) {
            mappingFile.deleteIfExists()
            return null
        }
        mappingFile.source().buffer().use {
            return UMFReader.read(it)
        }
    }

    override suspend fun writeCache(key: String, tree: MemoryMappingTree) {
        val mappingFile = if (hasStubs()) { unimined.getLocalCache().resolve("mappings") } else { minecraft.minecraftData.mcVersionFolder }.resolve("mappings-${key}.umf")
        mappingFile.parent?.createDirectories()
        mappingFile.bufferedWriter().use {
            tree.accept(UMFWriter.write(it::append))
        }
    }

    override suspend fun getTRMappings(
        remap: Pair<Namespace, Namespace>,
        remapLocals: Boolean,
    ) : (IMappingProvider.MappingAcceptor) -> Unit {
        val mappings = this.resolve()
        return { acceptor ->
            val srcName = remap.first
            val dstName = remap.second

            if (srcName !in mappings.namespaces) {
                throw IllegalArgumentException("Source namespace $srcName not found in mappings")
            }
            if (dstName !in mappings.namespaces) {
                throw IllegalArgumentException("Target namespace $dstName not found in mappings")
            }

            mappings.accept(EmptyMappingVisitor().delegator(object : Delegator() {
                lateinit var fromClassName: String
                lateinit var toClassName: String

                private fun memberOf(className: String, memberName: String, descriptor: String?): IMappingProvider.Member {
                    return IMappingProvider.Member(className, memberName, descriptor)
                }

                override fun visitClass(delegate: MappingVisitor, names: Map<Namespace, InternalName>): ClassVisitor? {
                    if (srcName in names && dstName in names) {
                        fromClassName = names[srcName]!!.toString()
                        toClassName = names[dstName]!!.toString()
                        acceptor.acceptClass(fromClassName, toClassName)
                    }
                    return super.visitClass(delegate, names)
                }

                override fun visitMethod(
                    delegate: ClassVisitor,
                    names: Map<Namespace, Pair<String, MethodDescriptor?>>
                ): MethodVisitor? {
                    if (srcName in names && dstName in names) {
                        val fromMethodName = names[srcName]!!.first
                        val fromMethodDesc = names[srcName]!!.second
                        val toMethodName = names[dstName]!!.first
                        val method = memberOf(fromClassName, fromMethodName, fromMethodDesc!!.toString())
                        acceptor.acceptMethod(method, toMethodName)
                    }
                    return if (remapLocals) {
                        super.visitMethod(delegate, names)
                    } else {
                        null
                    }
                }

                override fun visitField(
                    delegate: ClassVisitor,
                    names: Map<Namespace, Pair<String, FieldDescriptor?>>
                ): FieldVisitor? {
                    if (srcName in names && dstName in names) {
                        val fromFieldName = names[srcName]!!.first
                        val toFieldName = names[dstName]!!.first
                        acceptor.acceptField(memberOf(fromClassName, fromFieldName, names[srcName]?.second?.toString()), toFieldName)
                    }
                    return null
                }

                override fun visitParameter(
                    delegate: InvokableVisitor<*>,
                    index: Int?,
                    lvOrd: Int?,
                    names: Map<Namespace, String>
                ): ParameterVisitor? {
                    if (srcName in names && dstName in names && lvOrd != null) {
                        val fromArgName = names[srcName]!!
                        val toArgName = names[dstName]!!
                        acceptor.acceptMethodArg(memberOf(fromClassName, fromArgName, null), lvOrd, toArgName)
                    }
                    return null
                }

                override fun visitLocalVariable(
                    delegate: InvokableVisitor<*>,
                    lvOrd: Int,
                    startOp: Int?,
                    names: Map<Namespace, String>
                ): LocalVariableVisitor? {
                    if (srcName in names && dstName in names) {
                        val fromLocalVarName = names[srcName]!!
                        val toLocalVarName = names[dstName]!!
                        acceptor.acceptMethodVar(memberOf(fromClassName, fromLocalVarName, null), lvOrd, startOp ?: -1, -1, toLocalVarName)
                    }
                    return null
                }


            }))
        }
    }

    inner class MappingContentProvider(val dep: Dependency, val ext: String) : ContentProvider {

        override fun content(): BufferedSource {
            return mappings.getFiles(dep) { it.extension == ext }.singleFile.source().buffer()
        }

        override fun fileName(): String {
            return mappings.getFiles(dep) { it.extension == ext }.singleFile.name
        }

        override suspend fun resolve() {
            mappings.resolve()
        }

    }

}