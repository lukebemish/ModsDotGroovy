package org.groovymc.modsdotgroovy.gradle.tasks

import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.work.NormalizeLineEndings
import org.groovymc.modsdotgroovy.core.MapUtils
import org.groovymc.modsdotgroovy.core.Platform
import org.groovymc.modsdotgroovy.gradle.MDGConversionOptions
import org.groovymc.modsdotgroovy.transform.MDGBindingAdder

import javax.inject.Inject
import java.nio.file.Files

@CacheableTask
@CompileStatic
abstract class AbstractMDGConvertTask extends DefaultTask {
    private static final CompilerConfiguration MDG_COMPILER_CONFIG = new CompilerConfiguration().tap {
        targetBytecode = JDK17
        optimizationOptions['indy'] = true
    }

    @InputFile
    @NormalizeLineEndings
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getInput()

    @Optional
    @OutputFile
    abstract RegularFileProperty getOutput()

    @Input
    abstract Property<String> getOutputName()

    @CompileClasspath
    abstract ConfigurableFileCollection getMdgRuntimeFiles()

    @Input
    @Optional
    abstract MapProperty<String, Object> getBuildProperties()

    @Input
    @Optional
    abstract SetProperty<String> getEnvironmentBlacklist()

    @Input
    @Optional
    abstract Property<Platform> getPlatform()

    @Input
    abstract Property<String> getProjectVersion()

    @Input
    abstract Property<String> getProjectGroup()

    @Input
    abstract Property<MDGConversionOptions> getConversionOptions()

    /**
     * A file containing platform-specific details, such as the Minecraft version and loader version.
     * <p>Generated by a task that extends {@link AbstractGatherPlatformDetailsTask}</p>
     */
    @InputFile
    @NormalizeLineEndings
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getPlatformDetailsFile()

    @Inject
    protected abstract ProjectLayout getProjectLayout()

    AbstractMDGConvertTask() {
        // default to e.g. build/modsDotGroovyToToml/mods.toml
        output.convention(projectLayout.buildDirectory.dir('generated/modsDotGroovy/' + name.replaceFirst('ConvertTo', 'modsDotGroovyTo')).map((Directory dir) -> dir.file(outputName.get())))

        environmentBlacklist.convention(project.provider(() -> conversionOptions.get().environmentBlacklist.get()))
        buildProperties.convention(project.provider(() -> filterBuildProperties(project.extensions.extraProperties.properties, environmentBlacklist.get())))
        projectVersion.convention(project.provider(() -> project.version.toString()))
        projectGroup.convention(project.provider(() -> project.group.toString()))
    }

    protected abstract String writeData(Map data)

    protected static Map<String, Object> filterBuildProperties(final Map<String, Object> buildProperties, final Set<String> blacklist) {
        return buildProperties.findAll { entry ->
            for (String blacklistEntry in blacklist) {
                if (entry.key.containsIgnoreCase(blacklistEntry))
                    return false
            }
            return true
        }
    }

    @TaskAction
    void run() {
        final input = input.get().asFile
        if (!input.exists()) {
            logger.warn("Input file {} for task '{}' could not be found!", input, name)
            return
        }

        final Map data = MapUtils.recursivelyConvertToPrimitives(from(input))

        final outPath = output.get().asFile.toPath()
        if (outPath.parent !== null && !Files.exists(outPath.parent))
            Files.createDirectories(outPath.parent)

        Files.deleteIfExists(outPath)
        Files.writeString(outPath, writeData(data))
    }

    protected Map from(File script) {
        // The default Gradle classloader breaks the Java ServiceLoader, so we need to use our own classloader
        final ClassLoader mdgClassLoader = new URLClassLoader(mdgRuntimeFiles.files.collect { it.toURI().toURL() }.toArray(URL[]::new))

        final compilerConfig = new CompilerConfiguration(MDG_COMPILER_CONFIG)
        compilerConfig.classpathList = mdgClassLoader.URLs*.toString()
        //println "mdgClassLoader classpath: ${compilerConfig.classpath}"

        final bindingAdderTransform = new ASTTransformationCustomizer(MDGBindingAdder)
        final Platform platform = platform.get()
        // todo: support both single and multiplatform
//        if (platform !== Platform.FORGE)
//            bindingAdderTransform.annotationParameters = [className: "${platform.toString()}ModsDotGroovy"] as Map<String, Object>

        bindingAdderTransform.annotationParameters = [className: "MultiplatformModsDotGroovy"] as Map<String, Object>

        compilerConfig.addCompilationCustomizers(bindingAdderTransform)

        final Map bindingValues = [
                buildProperties: buildProperties.get(),
                platform: platform,
                version: projectVersion.get(),
                group: projectGroup.get(),
        ]

        final json = new JsonSlurper()
        (json.parse(platformDetailsFile.get().asFile) as Map<String, String>).each { key, value ->
            bindingValues[key] = value
        }

        final bindings = new Binding(bindingValues)
        final shell = new GroovyShell(mdgClassLoader, bindings, compilerConfig)
        // set context classloader to MDG classloader so that transitive dependencies work correctly
        shell.evaluate('Thread.currentThread().contextClassLoader = this.class.classLoader')
        return fromScriptResult(shell.evaluate(script))
    }

    @CompileDynamic
    private static Map fromScriptResult(def scriptResult) {
        return scriptResult.core.build()
    }
}
