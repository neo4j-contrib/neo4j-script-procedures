package scripts;

import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.impl.core.GraphProperties;
import org.neo4j.kernel.impl.core.GraphPropertiesProxy;
import org.neo4j.kernel.impl.core.NodeManager;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.PerformsWrites;
import org.neo4j.procedure.Procedure;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import static org.neo4j.helpers.collection.MapUtil.stringMap;

/**
 * This is an example showing how you could expose Neo4j's full text indexes as
 * two procedures - one for updating indexes, and one for querying by label and
 * the lucene query language.
 */
public class Scripts {

    public static final Object[] NO_OBJECTS = new Object[0];
    // This field declares that we need a GraphDatabaseService
    // as context when any procedure in this class is invoked
    @Context
    public GraphDatabaseService db;

    // This gives us a log instance that outputs messages to the
    // standard log, normally found under `data/log/console.log`
    @Context
    public Log log;

    private static final GraphPropertiesProxy NO_GRAPH_PROPERTIES = new GraphPropertiesProxy(null);
    private static GraphProperties graphProperties = NO_GRAPH_PROPERTIES;

    private static ThreadLocal<ScriptEngine> engine = new ThreadLocal<>();

    private GraphProperties graphProperties() {
        if (graphProperties == NO_GRAPH_PROPERTIES)
            graphProperties = ((GraphDatabaseAPI) db).getDependencyResolver().resolveDependency(NodeManager.class).newGraphProperties();
        return graphProperties;
    }
    private ScriptEngine getEngine() {
        if (engine.get() == null) {
            ScriptEngine js = new ScriptEngineManager().getEngineByName("nashorn");
            addFunctions(js,
                    "function label(s) org.neo4j.graphdb.Label.label(s)",
                    "function type(s) org.neo4j.graphdb.RelationshipType.withName(s)",
                    "function collection(it) { r=[]; while (it.hasNext()) r.push(it.next());  return Java.to(r); }");
            engine.set(js);
        }
        return engine.get();
    }

    private void addFunctions(ScriptEngine js, String...script) {
        try {
            for (String s : script) js.eval(s);
        } catch (ScriptException e) {
            throw new RuntimeException(e);
        }
    }

    @Procedure
    public Stream<Result> run(@Name("name") String name, @Name("params") List params) {
        try {
            ScriptEngine js = getEngine();
            Object function = js.get(name);
            if (function == null) {
                String code = (String) graphProperties().getProperty(name, null);
                if (code == null)
                    throw new RuntimeException("Function " + name + " not defined, use CALL function('name','code') ");
                else {
                    js.eval(code);
                }
            }
            js.put("db", db);
            js.put("log", log);
            Object value = ((Invocable) js).invokeFunction(name, params == null ? NO_OBJECTS : params.toArray());
            if (value instanceof Object[]) {
                return Stream.of((Object[]) value).map(Result::new);
            }
            if (value instanceof Iterable) {
                return StreamSupport.stream(((Iterable<?>)value).spliterator(),false).map(Result::new);
            }
            return Stream.of(new Result(value));
        } catch (ScriptException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Procedure
    @PerformsWrites
    public Stream<Result> function(@Name("name") String name, @Name("code") String code) {
        try {
            ScriptEngine js = getEngine();
            js.eval(code);
            GraphProperties props = graphProperties();
            boolean replaced = props.hasProperty(name);
            props.setProperty(name, code);
            return Stream.of(new Result(String.format("%s Function %s", replaced ? "Updated" : "Added", name)));
        } catch (ScriptException e) {
            throw new RuntimeException(e);
        }
    }

    @Procedure
    public Stream<Result> list() {
        return StreamSupport.stream(graphProperties.getPropertyKeys().spliterator(), false).map(Result::new);
    }

    public static class Result {
        public Object value;

        public Result(Object value) {
            this.value = value;
        }

    }

}
