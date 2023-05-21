package xyz.wagyourtail.unimined.internal.minecraft.patch.fabric

import com.google.gson.*
import net.fabricmc.mappingio.format.ZipReader
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.mapping.MappingNamespace
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.minecraft.transform.patch.FabricLikePatcher
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.task.ExportMappingsTask
import xyz.wagyourtail.unimined.api.task.RemapJarTask
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.mapping.task.ExportMappingsTaskImpl
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.AbstractMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.MinecraftJar
import xyz.wagyourtail.unimined.internal.mapping.at.AccessTransformerMinecraftTransformer
import xyz.wagyourtail.unimined.internal.mapping.aw.AccessWidenerMinecraftTransformer
import xyz.wagyourtail.unimined.minecraft.transform.merge.ClassMerger
import xyz.wagyourtail.unimined.util.FinalizeOnRead
import xyz.wagyourtail.unimined.util.LazyMutable
import xyz.wagyourtail.unimined.util.withSourceSet
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

abstract class FabricLikeMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider,
    providerName: String,
    val modJsonName: String,
    val accessWidenerJsonKey: String
): AbstractMinecraftTransformer(
    project,
    provider,
    providerName
), FabricLikePatcher {
    companion object {
        val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
    }

    val fabric: Configuration = project.configurations.maybeCreate(providerName.withSourceSet(provider.sourceSet)).also {
        project.configurations.getByName("implementation".withSourceSet(provider.sourceSet)).extendsFrom(it)
    }

    private val fabricJson: Configuration = project.configurations.detachedConfiguration()

    private val include: Configuration = project.configurations.maybeCreate("include".withSourceSet(provider.sourceSet))

    override var accessWidener: File? by FinalizeOnRead(null)

    protected abstract val ENVIRONMENT: String
    protected abstract val ENV_TYPE: String

    override val merger: ClassMerger = ClassMerger(
        { node, env ->
            if (env == EnvType.COMBINED) return@ClassMerger
            if (isAnonClass(node)) return@ClassMerger
            val visitor = node.visitAnnotation(ENVIRONMENT, true)
            visitor.visitEnum("value", ENV_TYPE, env.name)
            visitor.visitEnd()
        },
        { node, env ->
            if (env != EnvType.COMBINED) {
                val visitor = node.visitAnnotation(ENVIRONMENT, true)
                visitor.visitEnum("value", ENV_TYPE, env.name)
                visitor.visitEnd()
            }
        },
        { node, env ->
            if (env != EnvType.COMBINED) {
                val visitor = node.visitAnnotation(ENVIRONMENT, true)
                visitor.visitEnum("value", ENV_TYPE, env.name)
                visitor.visitEnd()
            }
        }
    )

    override var prodNamespace by FinalizeOnRead(MappingNamespace.INTERMEDIARY)

    @get:ApiStatus.Internal
    @set:ApiStatus.Internal
    var devMappings: Path? by FinalizeOnRead(LazyMutable {
        project.unimined.getLocalCache()
            .resolve("mappings")
            .createDirectories()
            .resolve("intermediary2named.jar")
            .apply {
                val export = ExportMappingsTaskImpl.ExportImpl().apply {
                    location = toFile()
                    type = ExportMappingsTask.MappingExportTypes.TINY_V2
                    sourceNamespace = MappingNamespace.OFFICIAL
                    //TODO: make this work properly with different devFallback than prod
                    targetNamespace = listOf(prodNamespace, provider.mappings.devNamespace)
                    renameNs[provider.mappings.devNamespace] = "named"
                }
                export.validate()
                export.exportFunc(provider.mappings.mappingTree)
            }
    })

    init {
        addMavens()
    }

    protected abstract fun addMavens()

    var mainClass: JsonObject? = null

    override fun apply() {
        val client = provider.side != EnvType.SERVER
        val server = provider.side != EnvType.CLIENT

        val dependencies = fabric.dependencies

        if (dependencies.isEmpty()) {
            throw IllegalStateException("No dependencies found for fabric provider")
        }

        if (dependencies.size > 1) {
            throw IllegalStateException("Multiple dependencies found for fabric provider")
        }

        val dependency = dependencies.first()
        var artifactString = ""
        if (dependency.group != null) {
            artifactString += dependency.group + ":"
        }
        artifactString += dependency.name
        if (dependency.version != null) {
            artifactString += ":" + dependency.version
        }
        artifactString += "@json"

        if (fabricJson.dependencies.isEmpty()) {
            fabricJson.dependencies.add(
                project.dependencies.create(
                    artifactString
                )
            )
        }

        val json = InputStreamReader(
            fabricJson.files(fabricJson.dependencies.last())
                .last()
                .inputStream()
        ).use { reader ->
            JsonParser.parseReader(reader).asJsonObject
        }

        val libraries = json.get("libraries")?.asJsonObject
        if (libraries != null) {
            libraries.get("common")?.asJsonArray?.forEach {
                createFabricLoaderDependency(it)
            }
            if (client) {
                libraries.get("client")?.asJsonArray?.forEach {
                    createFabricLoaderDependency(it)
                }
            }
            if (server) {
                libraries.get("server")?.asJsonArray?.forEach {
                    createFabricLoaderDependency(it)
                }
            }
        }

        mainClass = json.get("mainClass")?.asJsonObject

        if (devMappings != null) {
            provider.minecraftLibraries.dependencies.add(
                project.dependencies.create(project.files(devMappings))
            )
        }

        super.apply()
    }

    private fun createFabricLoaderDependency(it: JsonElement) {
        val dep: ModuleDependency = project.dependencies.create(
            it.asJsonObject.get("name").asString
        ) as ModuleDependency
        dep.isTransitive = false
        provider.minecraftLibraries.dependencies.add(dep)
    }

    override fun afterRemap(baseMinecraft: MinecraftJar): MinecraftJar =
        if (accessWidener != null) {
            val output = MinecraftJar(
                baseMinecraft,
                parentPath = project.unimined.getLocalCache().resolve("fabric").createDirectories()
            )
            if (!output.path.exists() || project.unimined.forceReload) {
                if (AccessWidenerMinecraftTransformer.transform(
                        accessWidener!!.toPath(),
                        baseMinecraft.mappingNamespace,
                        baseMinecraft.path,
                        output.path,
                        false,
                        project.logger
                    )
                ) {
                    output
                } else {
                    baseMinecraft
                }
            } else {
                output
            }
        } else baseMinecraft

    protected fun getIntermediaryClassPath(envType: EnvType): String {
        TODO("FIX THIS")
//        val remapClasspath = project.unimined.getLocalCache().resolve("remapClasspath.txt")
//        val s = arrayOf(
//            provider.minecraftLibraries.files.joinToString(File.pathSeparator),
//            provider.mods.modRemapper.preTransform(envType).joinToString(File.pathSeparator),
//            provider.getMinecraftWithMapping(envType, prodNamespace, prodNamespace).toString()
//        ).filter { it.isNotEmpty() }.joinToString(File.pathSeparator)

//        remapClasspath.writeText(s, options = arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
//        return remapClasspath.absolutePathString()
    }

    override fun afterRemapJarTask(remapJarTask: RemapJarTask, output: Path) {
        insertIncludes(output)
        insertAW(output)
    }

    private fun insertIncludes(output: Path) {
        ZipReader.openZipFileSystem(output, mapOf("mutable" to true)).use { fs ->
            val mod = fs.getPath(modJsonName)
            if (!Files.exists(mod)) {
                throw IllegalStateException("$modJsonName not found in jar")
            }
            val json = JsonParser.parseReader(InputStreamReader(Files.newInputStream(mod))).asJsonObject

            Files.createDirectories(fs.getPath("META-INF/jars/"))
            val includeCache = project.unimined.getLocalCache().resolve("includeCache")
            Files.createDirectories(includeCache)
            for (dep in include.dependencies) {
                val path = fs.getPath("META-INF/jars/${dep.name}-${dep.version}.jar")
                val cachePath = includeCache.resolve("${dep.name}-${dep.version}.jar")
                if (!Files.exists(cachePath)) {
                    Files.copy(
                        include.files(dep).first { it.extension == "jar" }.toPath(),
                        includeCache.resolve("${dep.name}-${dep.version}.jar"),
                        StandardCopyOption.REPLACE_EXISTING
                    )

                    ZipReader.openZipFileSystem(cachePath, mapOf("mutable" to true)).use { innerfs ->
                        val innermod = innerfs.getPath(modJsonName)
                        if (!Files.exists(innermod)) {
                            val innerjson = JsonObject()
                            innerjson.addProperty("schemaVersion", 1)
                            var artifactString = ""
                            if (dep.group != null) {
                                artifactString += dep.group!!.replace(".", "_") + "_"
                            }
                            artifactString += dep.name

                            innerjson.addProperty("id", artifactString)
                            innerjson.addProperty("version", dep.version)
                            innerjson.addProperty("name", dep.name)
                            val custom = JsonObject()
                            custom.addProperty("fabric-loom:generated", true)
                            innerjson.add("custom", custom)
                            Files.write(
                                innermod,
                                innerjson.toString().toByteArray(),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING
                            )
                        }
                    }
                }

                Files.copy(cachePath, path, StandardCopyOption.REPLACE_EXISTING)

                addIncludeToModJson(json, dep, "META-INF/jars/${dep.name}-${dep.version}.jar")
            }
            Files.write(mod, GSON.toJson(json).toByteArray(), StandardOpenOption.TRUNCATE_EXISTING)
        }
    }

    protected abstract fun addIncludeToModJson(json: JsonObject, dep: Dependency, path: String)

    private fun insertAW(output: Path) {
        if (accessWidener != null) {
            ZipReader.openZipFileSystem(output, mapOf("mutable" to true)).use { fs ->
                val mod = fs.getPath(modJsonName)
                if (!Files.exists(mod)) {
                    throw IllegalStateException("$modJsonName not found in jar")
                }
                val aw = accessWidener!!.toPath()
                var parent = aw.parent
                while (!fs.getPath(parent.relativize(aw).toString()).exists()) {
                    parent = parent.parent
                    if (parent.relativize(aw).toString() == aw.toString()) {
                        throw IllegalStateException("Access widener not found in jar")
                    }
                }
                val awPath = fs.getPath(parent.relativize(aw).toString())
                val json = JsonParser.parseReader(InputStreamReader(Files.newInputStream(mod))).asJsonObject
                json.addProperty(accessWidenerJsonKey, awPath.toString())
                Files.write(mod, GSON.toJson(json).toByteArray(), StandardOpenOption.TRUNCATE_EXISTING)
            }
        }
    }


    override fun at2aw(input: String, output: String, namespace: MappingNamespace) =
        at2aw(File(input), File(output), namespace)

    override fun at2aw(input: String, namespace: MappingNamespace) = at2aw(File(input), namespace)
    override fun at2aw(input: String, output: String) = at2aw(File(input), File(output))
    override fun at2aw(input: String) = at2aw(File(input))
    override fun at2aw(input: File) = at2aw(input, provider.mappings.devNamespace)
    override fun at2aw(input: File, namespace: MappingNamespace) = at2aw(
        input,
        project.extensions.getByType(SourceSetContainer::class.java).getByName("main").resources.srcDirs.first()
            .resolve("${project.name}.accesswidener"),
        namespace
    )

    override fun at2aw(input: File, output: File) = at2aw(input, output, provider.mappings.devNamespace)
    override fun at2aw(input: File, output: File, namespace: MappingNamespace): File {
        return AccessTransformerMinecraftTransformer.at2aw(
            input.toPath(),
            output.toPath(),
            namespace.namespace,
            provider.mappings.mappingTree,
            project.logger
        ).toFile()
    }

    override fun mergeAws(inputs: List<File>): File {
        return mergeAws(
            provider.mappings.devNamespace,
            inputs
        )
    }

    override fun mergeAws(namespace: MappingNamespace, inputs: List<File>): File {
        return mergeAws(
            project.extensions.getByType(SourceSetContainer::class.java).getByName("main").resources.srcDirs.first()
                .resolve("${project.name}.accesswidener"),
            namespace, inputs
        )
    }

    override fun mergeAws(output: File, inputs: List<File>): File {
        return mergeAws(output, provider.mappings.devNamespace, inputs)
    }

    override fun mergeAws(output: File, namespace: MappingNamespace, inputs: List<File>): File {
        return AccessWidenerMinecraftTransformer.mergeAws(
            inputs.map { it.toPath() },
            output.toPath(),
            namespace,
            provider.mappings,
            provider
        ).toFile()
    }

    override fun applyClientRunTransform(config: RunConfig) {
        config.mainClass = mainClass?.get("client")?.asString ?: config.mainClass
    }

    override fun applyServerRunTransform(config: RunConfig) {
        config.mainClass = mainClass?.get("server")?.asString ?: config.mainClass
    }
}