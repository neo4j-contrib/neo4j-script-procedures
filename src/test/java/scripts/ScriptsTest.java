package scripts;

import org.junit.Rule;
import org.junit.Test;

import org.neo4j.driver.v1.*;
import org.neo4j.harness.junit.Neo4jRule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.neo4j.bolt.BoltKernelExtension.Settings.connector;
import static org.neo4j.bolt.BoltKernelExtension.Settings.enabled;
import static org.neo4j.driver.v1.Values.parameters;

public class ScriptsTest
{
    // This rule starts a Neo4j instance for us
    @Rule
    public Neo4jRule neo4j = new Neo4jRule()

            // This is the Procedure we want to test
            .withProcedure( Scripts.class )

            // Temporary until Neo4jRule includes Bolt by default
            .withConfig( connector( 0, enabled ), "true" );

    @Test
    public void shouldAllowCreatingAndRunningJSProcedures() throws Throwable
    {
        // In a try-block, to make sure we close the driver after the test
        try( Driver driver = GraphDatabase.driver( "bolt://localhost" ) )
        {

            Session session = driver.session();

            // And given I have a node in the database
            long nodeId = session.run( "CREATE (p:User {name:'Brookreson'}) RETURN id(p)" )
                    .single()
                    .get( 0 ).asLong();


            // When I create a function to list all users
            session.run( "CALL scripts.function({name}, {code})", parameters( "name","users", "code","function users() collection(db.findNodes(label('User')))" ) );

            // Then I can find all users when calling that function by name
            ResultCursor result = session.run( "CALL scripts.run('users',null)" );
            Value value = result.single().get("value");
            System.out.println(value.asObject());
            assertThat( value.asNode().identity().asLong(), equalTo( nodeId ) );
        }
    }
}
