package com.hazelcast.client.pncounter;

import com.hazelcast.client.test.TestHazelcastFactory;
import com.hazelcast.config.CRDTReplicationConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.PNCounterConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.crdt.pncounter.BasePNCounterBasicIntegrationTest;
import com.hazelcast.spi.properties.GroupProperty;
import com.hazelcast.test.HazelcastParallelParametersRunnerFactory;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.After;
import org.junit.Before;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runners.Parameterized.UseParametersRunnerFactory;

import java.util.Collection;

import static java.util.Arrays.asList;

/**
 * Client implementation for basic
 * {@link com.hazelcast.crdt.pncounter.PNCounter} integration tests
 */
@RunWith(Parameterized.class)
@UseParametersRunnerFactory(HazelcastParallelParametersRunnerFactory.class)
@Category({QuickTest.class, ParallelTest.class})
public class ClientPNCounterBasicIntegrationTest extends BasePNCounterBasicIntegrationTest {

    @Parameters(name = "replicaCount:{0}")
    public static Collection<Object[]> parameters() {
        return asList(new Object[][]{
                {1},
                {2},
                {Integer.MAX_VALUE},
        });
    }

    private final TestHazelcastFactory hazelcastFactory = new TestHazelcastFactory();
    private HazelcastInstance[] members;
    private HazelcastInstance[] clients;

    @Parameter
    public int replicaCount;

    @After
    public void tearDown() {
        hazelcastFactory.terminateAll();
    }

    @Before
    public void setup() {
        final PNCounterConfig counterConfig = new PNCounterConfig()
                .setName("default")
                .setReplicaCount(replicaCount)
                .setStatisticsEnabled(true);
        final Config config = new Config()
                .setProperty(GroupProperty.PARTITION_COUNT.getName(), "5")
                .setCRDTReplicationConfig(new CRDTReplicationConfig().setReplicationPeriodMillis(200)
                                                                     .setMaxConcurrentReplicationTargets(Integer.MAX_VALUE))
                .addPNCounterConfig(counterConfig);
        members = hazelcastFactory.newInstances(config, 2);
        clients = new HazelcastInstance[]{hazelcastFactory.newHazelcastClient(), hazelcastFactory.newHazelcastClient()};
    }

    @Override
    protected HazelcastInstance getInstance1() {
        return clients[0];
    }

    @Override
    protected HazelcastInstance getInstance2() {
        return clients[1];
    }

    @Override
    protected HazelcastInstance[] getMembers() {
        return members;
    }
}