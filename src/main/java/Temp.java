
import com.complexible.stardog.api.Connection;
import com.complexible.stardog.api.ConnectionConfiguration;
import com.complexible.stardog.api.ConnectionPool;
import com.complexible.stardog.api.ConnectionPoolConfig;
import com.complexible.stardog.api.admin.AdminConnection;
import com.complexible.stardog.api.admin.AdminConnectionConfiguration;
import com.complexible.stardog.db.DatabaseOptions;
import com.complexible.stardog.reasoning.ReasoningOptions;
import com.complexible.stardog.reasoning.api.ReasoningType;
import org.openrdf.model.IRI;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.SimpleValueFactory;
import org.openrdf.rio.RDFFormat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


public class Temp {





    public static void main(String[] args) throws Exception {


        AtomicBoolean clearingLock = new AtomicBoolean(true);


        SimpleValueFactory vf = SimpleValueFactory.getInstance();
        StardogServiceTemp stardogService = getStardogService();
        stardogService.purgeDatabase("testDatabase");
        stardogService.shutdown();



        new Thread() {
            @Override
            public void run() {


                try {
                    StardogServiceTemp stardogService = getStardogService();


                    while (true) {


                        clearDatabase(stardogService);

                        clearingLock.set(false);


                        // upload some data
                        for (int i = 1; i <= 9; i++) {

                            System.out.println("Uploading: "+i);
                            Connection connection1 = stardogService.connectionPoolWithoutReasoning.obtain();
                            connection1.begin();
                            connection1.add().io().format(RDFFormat.TURTLE).stream(Temp.class.getClassLoader().getResourceAsStream("" + i + ".ttl"));
                            connection1.commit();
                            stardogService.connectionPoolWithoutReasoning.release(connection1);

                        }

                        // wait for query in other thread to complete
                        while (!clearingLock.get()){
                            Thread.sleep(1);
                        }

                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }


            }
        }.start();


        new Thread() {
            @Override
            public void run() {

                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                try {
                    StardogServiceTemp stardogService = getStardogService();

                    final int[] counter = {0};
                    while (true) {

                        // wait for clearing database to be done
                        while(clearingLock.get()){
                            Thread.sleep(1);
                        }


                        // print a counter every once in a while
                        if (counter[0]++ % 100 == 0) {
                            System.out.println("Number of tries for ASK query: "+counter[0]);
                        }

                        // at some point, now or in the future, there should be data in stardog


                        Connection connection = stardogService.connectionPool.obtain();
                        boolean dataExists  = connection.ask("ASK {<http://example.com/base/journalpost--journalpost%3A200033--test--2016-03-04T18%3A28%3A42> arkiv:arkivskaper ?anything}").execute();
                        stardogService.connectionPool.release(connection);

//                        WORKS WITHOUT REASONING!!!
//                        Connection connection = stardogService.connectionPoolWithoutReasoning.obtain();
//                        boolean dataExists  = connection.ask("ASK {<http://example.com/base/journalpost--journalpost%3A200033--test--2016-03-04T18%3A28%3A42> arkiv:parent+ / arkiv:arkivskaper ?anything}").execute();
//                        stardogService.connectionPoolWithoutReasoning.release(connection);


                        // check if all the data made it to stardog
                        if (dataExists) {
                            clearingLock.set(true);
                            System.out.println(":) ASK query result was TRUE");
                            counter[0] = 0;
                        }




                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }.start();


    }

    private static void clearDatabase(StardogServiceTemp stardogService) {
        Connection connection = stardogService.connectionPoolWithoutReasoning.obtain();
        connection.begin();
        connection.remove().all();
        connection.commit();
        stardogService.connectionPoolWithoutReasoning.release(connection);


        stardogService.populateWithInitialData();
    }

    static StardogServiceTemp getStardogService() {

        StardogServiceTemp stardogServiceTemp = new StardogServiceTemp();

        return stardogServiceTemp;
    }


}

class StardogServiceTemp {

    private final AdminConnectionConfiguration adminConnectionConfiguration;
    public ConnectionPool connectionPool;
    public ConnectionPool connectionPoolWithoutReasoning;

    StardogServiceTemp() {

        String serverUrl = String.format("http://%s:%s", "localhost", "5820");

        ConnectionConfiguration connectionConfiguration = ConnectionConfiguration
            .to("testDatabase")
            .server(serverUrl)
            .reasoning(true)
            .credentials("admin", "admin");

        ConnectionConfiguration connectionConfigurationWithoutReasoning = ConnectionConfiguration
            .to("testDatabase")
            .server(serverUrl)
            .reasoning(false)
            .credentials("admin", "admin");

        adminConnectionConfiguration = AdminConnectionConfiguration
            .toServer(serverUrl)
            .credentials("admin", "admin");


        try (AdminConnection adminConnection = adminConnectionConfiguration.connect()) {

            if(!adminConnection.list().contains("testDatabase")){
                adminConnection
                    .disk("testDatabase")
                    .set(ReasoningOptions.SCHEMA_GRAPHS, Collections.singletonList(ONTOLOGY_GRAPH))
                    .set(ReasoningOptions.REASONING_TYPE, ReasoningType.SL)
                    .set(ReasoningOptions.CONSISTENCY_AUTOMATIC, true)
                    .set(DatabaseOptions.QUERY_ALL_GRAPHS, true)
                    .create();
            }


        }

        connectionPool = ConnectionPoolConfig
            .using(connectionConfiguration)
            .minPool(1)
            .maxPool(1000)
            .expiration(1, TimeUnit.HOURS)
            .blockAtCapacity(1, TimeUnit.MINUTES)
            .create();

        connectionPoolWithoutReasoning = ConnectionPoolConfig
            .using(connectionConfigurationWithoutReasoning)
            .minPool(1)
            .maxPool(1000)
            .expiration(1, TimeUnit.HOURS)
            .blockAtCapacity(1, TimeUnit.MINUTES)
            .create();


        populateWithInitialData();


    }

    public void populateWithInitialData() {
        setNamespaces();
        try {
            uploadOntology();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void setNamespaces() {
        Connection connection = connectionPool.obtain();

        connection.namespaces().add("arkiv", "http://www.arkivverket.no/standarder/noark5/arkivstruktur/");

        connectionPool.release(connection);
    }

    private void uploadOntology() throws IOException {
        uploadAndReplace(Temp.class.getClassLoader().getResourceAsStream("ontology.ttl"), ONTOLOGY_GRAPH, RDFFormat.TURTLE);
    }

    public void uploadAndReplace(InputStream data, IRI namedGraph, RDFFormat format) throws IOException {

        Connection connection = connectionPool.obtain();

        connection.begin();
        connection.add().io().format(format).context(namedGraph).stream(data);
        connection.commit();
        connectionPool.release(connection);


    }

    public static final String NS = "http://www.arkivverket.no/standarder/noark5/arkivstruktur/";

    private static ValueFactory factory = SimpleValueFactory.getInstance();

    public static final IRI ONTOLOGY_GRAPH = factory.createIRI(NS + "ontologyGraph");


    public void purgeDatabase(String database) {

        dropAndRecreateDatabase(database);

        populateWithInitialData();



    }

    private void dropAndRecreateDatabase(String database) {
        try (AdminConnection adminConnection = adminConnectionConfiguration.connect()) {


            try {
                adminConnection.drop(database);

            } catch (Exception e) {
                e.printStackTrace();
            }

            adminConnection
                .disk(database)
                .set(ReasoningOptions.SCHEMA_GRAPHS, Collections.singletonList(ONTOLOGY_GRAPH))
                .set(ReasoningOptions.REASONING_TYPE, ReasoningType.SL)
                .set(ReasoningOptions.CONSISTENCY_AUTOMATIC, true)
                .set(DatabaseOptions.QUERY_ALL_GRAPHS, true)
                .create();


        }
    }

    public void shutdown() {
        connectionPool.shutdown();
        connectionPoolWithoutReasoning.shutdown();
    }


}
