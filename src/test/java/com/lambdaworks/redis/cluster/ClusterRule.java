package com.lambdaworks.redis.cluster;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.lambdaworks.redis.RedisAsyncConnection;
import com.lambdaworks.redis.RedisAsyncConnectionImpl;
import com.lambdaworks.redis.TestSettings;
import com.lambdaworks.redis.cluster.models.partitions.ClusterPartitionParser;
import com.lambdaworks.redis.cluster.models.partitions.Partitions;
import com.lambdaworks.redis.cluster.models.partitions.RedisClusterNode;

/**
 * @author <a href="mailto:mpaluch@paluch.biz">Mark Paluch</a>
 */
public class ClusterRule implements TestRule {

    private RedisClusterClient clusterClient;
    private int[] ports;
    private Map<Integer, RedisAsyncConnectionImpl<?, ?>> connectionCache = Maps.newHashMap();

    public ClusterRule(RedisClusterClient clusterClient, int... ports) {
        this.clusterClient = clusterClient;
        this.ports = ports;

        for (int port : ports) {
            RedisAsyncConnectionImpl<String, String> connection = clusterClient.connectAsyncImpl(new InetSocketAddress(
                    "localhost", port));
            connectionCache.put(port, connection);
        }
    }

    @Override
    public Statement apply(final Statement base, Description description) {

        final Statement beforeCluster = new Statement() {
            @Override
            public void evaluate() throws Throwable {
                List<Future<?>> futures = Lists.newArrayList();

                for (RedisAsyncConnection<?, ?> connection : connectionCache.values()) {
                    futures.add(connection.flushall());
                }

                for (Future<?> future : futures) {
                    future.get();
                }
            }
        };

        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                beforeCluster.evaluate();

                base.evaluate();

            }
        };
    }

    public boolean isStable() {

        for (int port : ports) {
            RedisAsyncConnectionImpl<String, String> connection = clusterClient.connectAsyncImpl(new InetSocketAddress(
                    TestSettings.host(), port));
            try {
                String info = connection.clusterInfo().get();
                if (info != null && info.contains("cluster_state:ok")) {

                    String s = connection.clusterNodes().get();
                    Partitions parse = ClusterPartitionParser.parse(s);

                    for (RedisClusterNode redisClusterNode : parse) {
                        if (redisClusterNode.getFlags().contains(RedisClusterNode.NodeFlag.FAIL)
                                || redisClusterNode.getFlags().contains(RedisClusterNode.NodeFlag.EVENTUAL_FAIL)
                                || redisClusterNode.getFlags().contains(RedisClusterNode.NodeFlag.HANDSHAKE)) {
                            return false;
                        }
                    }
                } else {
                    return false;
                }
            } catch (Exception e) {
                // nothing to do
            } finally {
                connection.close();
            }
        }

        return true;
    }

    public void flushdb() {
        try {
            for (RedisAsyncConnection<?, ?> connection : connectionCache.values()) {
                connection.flushdb().get();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void clusterReset() {
        try {

            for (RedisAsyncConnectionImpl<?, ?> connection : connectionCache.values()) {
                connection.clusterReset(false).get();
                connection.clusterReset(true).get();
                connection.clusterFlushslots().get();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void meet(String host, int port) {
        for (RedisAsyncConnectionImpl<?, ?> redisAsyncConnection : connectionCache.values()) {
            redisAsyncConnection.clusterMeet(host, port);
        }
    }

    public RedisClusterClient getClusterClient() {
        return clusterClient;
    }
}
