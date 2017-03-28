package scripts;

import org.junit.Rule;
import org.junit.Test;

import org.neo4j.driver.v1.*;
import org.neo4j.harness.junit.Neo4jRule;

import static java.util.Arrays.asList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.neo4j.driver.v1.Values.parameters;

public class ScriptsTest
{
    @Rule
    public Neo4jRule neo4j = new Neo4jRule().withProcedure( Scripts.class ).withFunction( Scripts.class );

    @Test
    public void shouldAllowCreatingAndRunningJSProcedures() throws Throwable
    {
        try( Driver driver = GraphDatabase.driver( neo4j.boltURI() ,
                Config.build().withEncryptionLevel(Config.EncryptionLevel.NONE).toConfig()) )
        {

            try (Session session = driver.session()) {

                long nodeId = session.run("CREATE (p:User {name:'Brookreson'}) RETURN id(p)")
                        .single()
                        .get(0).asLong();


                session.run("CALL scripts.function({name}, {code})", parameters("name", "users", "code", "function users() { return collection(db.findNodes(label('User'))); }"));

                StatementResult result = session.run("CALL scripts.run('users',null)");
                Value value = result.single().get("value");
                System.out.println(value.asObject());
                assertThat(value.asNode().id(), equalTo(nodeId));
                result = session.run("RETURN scripts.run('users') AS value");
                value = result.single().get("value");
                System.out.println(value.asObject());
                assertThat(value.get(0).asNode().id(), equalTo(nodeId));
            }
        }
    }
}
