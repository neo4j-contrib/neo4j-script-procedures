package scripts;

import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.impl.core.EmbeddedProxySPI;
import org.neo4j.kernel.impl.core.GraphProperties;
import org.neo4j.kernel.impl.core.GraphPropertiesProxy;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.PerformsWrites;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.UserFunction;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import static org.neo4j.helpers.collection.MapUtil.stringMap;

public class Scripts {

    private static String PREFIX = "script.function.";
    public static final Object[] NO_OBJECTS = new Object[0];

    @Context
    public GraphDatabaseAPI db;

    @Context
    public Log log;

    private static final GraphPropertiesProxy NO_GRAPH_PROPERTIES = new GraphPropertiesProxy(null);
    private static GraphProperties graphProperties = NO_GRAPH_PROPERTIES;

    private static ThreadLocal<ScriptEngine> engine = new ThreadLocal<>();

    private GraphProperties graphProperties() {
        if (graphProperties == NO_GRAPH_PROPERTIES)
            graphProperties = this.db.getDependencyResolver().resolveDependency(EmbeddedProxySPI.class).newGraphPropertiesProxy();
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

    @UserFunction("scripts.run")
    public Object runFunction(@Name("name") String name, @Name(value="params",defaultValue="[]") List<Object> params) throws ScriptException, NoSuchMethodException {
        ScriptEngine js = getEngine();
        String code = (String) graphProperties().getProperty(PREFIX + name, null);
        if (code == null)
            throw new RuntimeException("Function " + name + " not defined, use CALL function('name','code') ");

        js.put("db", db);
        js.put("log", log);
        js.eval(String.format("function %s(){ return (%s).apply(this, arguments) }", name, code));
        return ((Invocable) js).invokeFunction(name, params == null ? NO_OBJECTS : params.toArray());
    }

    @Procedure
    public Stream<Result> run(@Name("name") String name, @Name(value="params",defaultValue="[]") List<Object> params) throws ScriptException, NoSuchMethodException {
        Object value = runFunction(name, params); 
        if (value instanceof Object[]) {
             return Stream.of((Object[]) value).map(Result::new);
         }
         if (value instanceof Iterable) {
             return StreamSupport.stream(((Iterable<?>)value).spliterator(),false).map(Result::new);
         }
         return Stream.of(new Result(value));
    }

    @Procedure(mode=Mode.WRITE)
    public Stream<Result> function(@Name("name") String name, @Name("code") String code) throws ScriptException {
        ScriptEngine js = getEngine();
        js.eval("function(){" + code + "}");
        GraphProperties props = graphProperties();
        boolean replaced = props.hasProperty(PREFIX + name);
        props.setProperty(PREFIX + name, code);
        return Stream.of(new Result(String.format("%s Function %s", replaced ? "Updated" : "Added", name)));
    }

    @Procedure(mode=Mode.WRITE)
    public Stream<Result> delete(@Name("name") String name) {
        GraphProperties props = graphProperties();
        props.removeProperty(PREFIX + name);
        return Stream.of(new Result(String.format("Function '%s' removed", name)));
    }


    @Procedure
    public Stream<Result> list() {
        return StreamSupport.stream(graphProperties.getPropertyKeys().spliterator(), false).filter(s -> s.startsWith(PREFIX )).map(Result::new);
    }

    public static class Result {
        public Object value;

        public Result(Object value) {
            this.value = value;
        }

    }

}
