package cucumber.runtime.groovy;

import cucumber.runtime.Backend;
import cucumber.runtime.ClassFinder;
import cucumber.runtime.CucumberException;
import cucumber.runtime.Glue;
import cucumber.runtime.filter.TagPredicate;
import cucumber.runtime.io.MultiLoader;
import cucumber.runtime.io.Resource;
import cucumber.runtime.io.ResourceLoader;
import cucumber.runtime.io.ResourceLoaderClassFinder;
import cucumber.runtime.snippets.FunctionNameGenerator;
import cucumber.runtime.snippets.SnippetGenerator;
import gherkin.pickles.PickleStep;
import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import io.cucumber.stepexpression.TypeRegistry;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.InvokerInvocationException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static cucumber.runtime.io.MultiLoader.packageName;

public class GroovyBackend implements Backend {
    public static ThreadLocal<GroovyBackend> instanceThreadLocal = new ThreadLocal<GroovyBackend>();
    private final Set<Class> scripts = new HashSet<Class>();
    private  SnippetGenerator snippetGenerator;
    private final ResourceLoader resourceLoader;
    private final GroovyShell shell;
    private final ClassFinder classFinder;
    private TypeRegistry typeRegistry;
    private Collection<Closure> worldClosures = new LinkedList<Closure>();
    private GroovyWorld world;
    private Glue glue;

    public static GroovyBackend getInstance(){
        return instanceThreadLocal.get();
    }

    private static GroovyShell createShell() {
        CompilerConfiguration compilerConfig = new CompilerConfiguration();
        // Probably not needed:
        // compilerConfig.addCompilationCustomizers(new ASTTransformationCustomizer(ThreadInterrupt.class));
        return new GroovyShell(Thread.currentThread().getContextClassLoader(), new Binding(), compilerConfig);
    }

    /**
     * The constructor called by reflection by default.
     *
     * @param resourceLoader
     * @param typeRegistry
     */
    public GroovyBackend(ResourceLoader resourceLoader, TypeRegistry typeRegistry) {
        this(createShell(), resourceLoader, typeRegistry);
    }

    public GroovyBackend(GroovyShell shell, ResourceLoader resourceLoader, TypeRegistry typeRegistry) {
        this.shell = shell;
        this.resourceLoader = resourceLoader;
        this.typeRegistry=typeRegistry;
        instanceThreadLocal.set(this);
        classFinder = new ResourceLoaderClassFinder(resourceLoader, shell.getClassLoader());
        this.snippetGenerator = new SnippetGenerator(new GroovySnippet(),typeRegistry.parameterTypeRegistry());
    }

    @Override
    public void loadGlue(Glue glue, List<String> gluePaths) {
        this.glue = glue;
        final Binding context = shell.getContext();

        for (String gluePath : gluePaths) {
            // Load sources
            try {
                for (Resource resource : resourceLoader.resources(gluePath, ".groovy")) {
                    Script script = parse(resource);
                    runIfScript(context, script);
                }
            }catch(IllegalArgumentException iae){
                for (Resource resource : resourceLoader.resources(MultiLoader.CLASSPATH_SCHEME + gluePath, ".groovy")) {
                    Script script = parse(resource);
                    runIfScript(context, script);
                }
            }
            // Load compiled scripts
            for (Class<? extends Script> glueClass : classFinder.getDescendants(Script.class, packageName(gluePath))) {
                try {
                    Script script = glueClass.getConstructor(Binding.class).newInstance(context);
                    runIfScript(context, script);
                } catch (Exception e) {
                    throw new CucumberException(e);
                }
            }
        }
    }

    private void runIfScript(Binding context, Script script) {
        Class scriptClass = script.getMetaClass().getTheClass();
        if (isScript(script) && !scripts.contains(scriptClass)) {
            script.setBinding(context);
            script.run();
            scripts.add(scriptClass);
        }
    }


    @Override
    public void buildWorld() {
        world = new GroovyWorld();
        for (Closure closure : worldClosures) {
            world.registerWorld(closure.call());
        }
    }

    private Script parse(Resource resource) {
        try {
            return shell.parse(new InputStreamReader(resource.getInputStream(), "UTF-8"), resource.getAbsolutePath());
        } catch (IOException e) {
            throw new CucumberException(e);
        }
    }

    private boolean isScript(Script script) {
        return DefaultGroovyMethods.asBoolean(script.getMetaClass().respondsTo(script, "main"));
    }

    @Override
    public void disposeWorld() {
        this.world = null;
    }

    @Override
    public List<String> getSnippet(PickleStep step, String keyword, FunctionNameGenerator functionNameGenerator) {
        return snippetGenerator.getSnippet(step, keyword, null);
    }

    public void addStepDefinition(String regexp, long timeoutMillis, Closure body) {
        glue.addStepDefinition(new GroovyStepDefinition(regexp, timeoutMillis, body, currentLocation(), this, typeRegistry));
    }

    public void registerWorld(Closure closure) {
        worldClosures.add(closure);
    }

    public void addBeforeHook(TagPredicate tagPredicate, long timeoutMillis, int order, Closure body) {
        glue.addBeforeHook(new GroovyHookDefinition(tagPredicate, timeoutMillis, order, body, currentLocation(), this));
    }

    public void addAfterHook(TagPredicate tagPredicate, long timeoutMillis, int order, Closure body) {
        glue.addAfterHook(new GroovyHookDefinition(tagPredicate, timeoutMillis, order, body, currentLocation(), this));
    }

    public void addBeforeStepHook(TagPredicate tagPredicate, long timeoutMillis, int order, Closure body) {
        glue.addBeforeStepHook(new GroovyHookDefinition(tagPredicate, timeoutMillis, order, body, currentLocation(), this));
    }

    public void addAfterStepHook(TagPredicate tagPredicate, long timeoutMillis, int order, Closure body) {
        glue.addAfterStepHook(new GroovyHookDefinition(tagPredicate, timeoutMillis, order, body, currentLocation(), this));
    }

    public void invoke(Closure body, Object[] args) throws Throwable {
        body.setResolveStrategy(Closure.DELEGATE_FIRST);
        body.setDelegate(world);
        try {
            body.call(args);
        } catch (InvokerInvocationException e) {
            throw e.getCause();
        }
    }

    GroovyWorld getGroovyWorld() {
        return world;
    }

    private static StackTraceElement currentLocation() {
        Throwable t = new Throwable();
        StackTraceElement[] stackTraceElements = t.getStackTrace();
        for (StackTraceElement stackTraceElement : stackTraceElements) {
            if (isGroovyFile(stackTraceElement.getFileName())) {
                return stackTraceElement;
            }
        }
        throw new RuntimeException("Couldn't find location for step definition");
    }

    private static boolean isGroovyFile(String fileName) {
        return fileName != null && fileName.endsWith(".groovy");
    }
}
