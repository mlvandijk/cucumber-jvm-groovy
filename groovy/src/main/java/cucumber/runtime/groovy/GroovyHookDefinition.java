package cucumber.runtime.groovy;

import cucumber.api.Scenario;
import cucumber.runtime.HookDefinition;
import cucumber.runtime.Timeout;
import cucumber.runtime.filter.TagPredicate;
import gherkin.pickles.PickleTag;
import groovy.lang.Closure;

import java.util.Collection;

public class GroovyHookDefinition implements HookDefinition {
    private final TagPredicate tagPredicate;
    private final long timeoutMillis;
    private final int order;
    private final Closure body;
    private final GroovyBackend backend;
    private final StackTraceElement location;

    public GroovyHookDefinition(
            TagPredicate tagPredicate,
            long timeoutMillis,
            int order,
            Closure body,
            StackTraceElement location,
            GroovyBackend backend) {

        this.tagPredicate = tagPredicate;
        this.timeoutMillis = timeoutMillis;
        this.order = order;
        this.body = body;
        this.location = location;
        this.backend = backend;
    }

    @Override
    public String getLocation(boolean detail) {
        return location.getFileName() + ":" + location.getLineNumber();
    }

    @Override
    public void execute(final Scenario scenario) throws Throwable {
        Timeout.timeout(new Timeout.Callback<Object>() {
            @Override
            public Object call() throws Throwable {
                backend.invoke(body, new Object[]{scenario});
                return null;
            }
        }, timeoutMillis);
    }

    @Override
    public boolean matches(Collection<PickleTag> tags) {
        return tagPredicate.apply(tags);
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public boolean isScenarioScoped() {
        return false;
    }
}

