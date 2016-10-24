package se.diabol.scrolls.engine

import freemarker.cache.ClassTemplateLoader
import freemarker.cache.FileTemplateLoader
import freemarker.cache.MultiTemplateLoader
import freemarker.cache.TemplateLoader
import freemarker.template.*

import java.nio.charset.StandardCharsets;

class ScrollsGenerator {

    def config
    def plugins
    Configuration freemarkerConfig

    ScrollsGenerator(config, options, plugins) {
        this.config = config
        this.freemarkerConfig = initializeFreemarker(options.templates)
        this.plugins = plugins
    }

    /**
     * Initialize FreeMarker with support for loading templates from both path (when set with --templates option) and
     * classpath resource (which is the default)
     *
     * @param config
     * @return
     */
    private Configuration initializeFreemarker(templates) {
        Configuration freemarkerConfig = new Configuration()
        freemarkerConfig.defaultEncoding = StandardCharsets.UTF_8.name()
        freemarkerConfig.templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
        freemarkerConfig.logTemplateExceptions = false

        if (templates) {
            TemplateLoader[] loaders = new TemplateLoader[2]
            loaders[0] = new FileTemplateLoader(new File(templates as String))
            loaders[1] = new ClassTemplateLoader(getClass(), '/')
            freemarkerConfig.setTemplateLoader(new MultiTemplateLoader(loaders))
        } else {
            freemarkerConfig.setClassForTemplateLoading(getClass(), '/')
        }

        return freemarkerConfig
    }

    def generateHtmlReport(Map header, Map reports) {
        def templateNameToRead = config.scrolls.templateName ?: 'scrolls-html.ftl'

        Template template
        try {
            print "Parsing template ${templateNameToRead}..."
            template = freemarkerConfig.getTemplate(templateNameToRead)
            println "OK"
        } catch (all) {
            println "FAIL!"
            throw new RuntimeException(all.message)
        }

        def parent = new File(config.scrolls.outputDirectory as String)
        try {
            print "Preparing output directory ${config.scrolls.outputDirectory}..."
            prepareOutputDirectory(parent)
            println "OK"
        } catch (all) {
            println "FAIL!"
            throw all
        }

        Map dataModel = [header: header, reports: reports]
        new File(parent, 'index.html').withWriter {
            try {
                print "Processing template..."
                template.process(dataModel, it)
                println "OK"
            } catch (TemplateException e) {
                println "FAIL!"
                throw new RuntimeException("Failed to process template, dataModel: ${dataModel}.\n${e.message}")
            }
        }
    }

    def prepareOutputDirectory(File directory) {
        if (directory.exists() && directory.isFile()) {
            throw new RuntimeException("File exists with same name as ${config.scrolls.outputDirectory}. Please rename file, or change output directory.")
        }

        if (!directory.exists() && !directory.mkdirs()) {
            throw new RuntimeException("Failed to created output directory: ${config.scrolls.outputDirectory}")
        }
    }

    def generate(Map oldVersion, Map newVersion) {
        Map header = [
                component: config.scrolls.component,
                date: new Date().format("yyyy-MM-dd HH:mm:ss"),
                oldVersion: oldVersion.version,
                newVersion: newVersion.version,
        ]

        println "Collecting data..."

        Map versions = [old: oldVersion, new: newVersion]
        Map reports = [:]
        Map executions = buildExecutionMap()

        // Run the plugins that require versions (and remove them from later execution)
        executions.remove('versions').each {
            print "  from ${it.name}..."
            reports[it.name] = it.plugin.generate(versions)
            println "OK"
        }

        // TODO: Replace hackish loopCounter with topological sorting of dependencies (with cycle detection before running!)
        int loopCounter = 0
        while (executions.keySet().size() > 0) {
            def names = executions.keySet()
            names.each {
                if (it in reports) {
                    executions[it].each {
                        print "  from ${it.name}..."
                        reports[it.name] = it.plugin.generate(reports[it.config.inputFrom])
                        println "OK"
                    }
                    executions.remove(it)
                } else {
                    loopCounter += 1
                }
            }

            if (loopCounter == 10) {
                throw new RuntimeException("Failed to resolve plugin dependencies, please make sure your configuration has no cycles.")
            }
        }

        generateHtmlReport(header, reports)
    }

    def buildExecutionMap() {
        Map executions = [:]

        // Build call time lists (considering dependencies to be very simple)
        plugins.each {
            name, plugin ->
                def pluginConfig = config."${name}" as Map
                def depends = pluginConfig.inputFrom ?: 'versions'
                def insert = [name: name, plugin: plugin, config: pluginConfig]

                if (depends in executions) {
                    executions[depends] << insert
                } else {
                    executions[depends] = [insert]
                }
        }

        return executions
    }
}
