# lettuce 3.3.Final RELEASE NOTES

lettuce 3.3 introduces a variety of changes: It features changes to cluster support,
new Geo commands for the upcoming Redis 3.2 release and allows batching, an extension
to pipelining.

The documentation within the lettuce wiki was overhauled.
Meet the very new wiki at https://github.com/mp911de/lettuce/wiki

The test stability issue was addressed by running infinite testing jobs.
Instabilities were caused due to missing synchronization and relying too much on timing.
However, the real life teaches us, not to rely on time, rather on hard facts whether
a cluster node is available, or a command is written out to the transport.
The tests are improved and fail less often.

I'm very excited to present you lettuce 3.3.Final. Read on with the details or jump
to the end of the file to find the summary and the download links.


Redis Cluster: Node connections
-------------------------------
lettuce's cluster support is enhanced by a couple of features. Users can obtain
a connection to the particular cluster nodes by specifying either the nodeId or
host and port. The new methods are available on two new interfaces:

 * RedisAdvancedClusterConnection
 * RedisAdvancedClusterAsyncConnection

Example code:

```java
RedisAdvancedClusterAsyncConnection<String, String> connection = clusterClient.connectClusterAsync();
RedisClusterAsyncConnection<String, String> nodeConnection = connection
                           .getConnection("adf7f86efa42d903bcd93c5bce72397fe52e91bb");

...

RedisClusterAsyncConnection<String, String> nodeConnection = connection.getConnection("localhost", 7379);
```

You are free to operate on these connections. Connections can be bound to specific
hosts or nodeId's. Connections bound to a nodeId will always stick to the nodeId,
even if the nodeId is handled by a different host. Requests to unknown nodeId's
or host/ports that are not part of the cluster are rejected.
Do not close the connections. Otherwise, unpredictable behavior will occur.
Keep also in mind, that the node connections are used by the cluster connection
itself to perform cluster operations: If you block one connection all other users
of the cluster connection might be affected.

The cluster client handles ASK redirection the same way as MOVED redirection.
It is transparent to the client. Dispatching the commands between the particular
cluster connections was optimized, and the performance was improved.


Cluster topology view refreshing
--------------------------------
Another cluster-related feature is the cluster topology view refresh.
Reloading the partitions was possible since lettuce 3.0. Version 3.3 enabled the
reloading on a regular basis in the background. The background update will close
connections to nodeId's/hosts that are no longer part of the cluster. You can
enable the refresh job (disabled by default) and specify the
interval (defaults to 60 seconds). The refresh can be configured in the ClusterClientOptions.

Example code:

```java
clusterClient.setOptions(new ClusterClientOptions.Builder()
                                 .refreshClusterView(true)
                                 .refreshPeriod(5, TimeUnit.SECONDS)
                                 .build());

RedisAdvancedClusterAsyncConnection<String, String> clusterConnection = clusterClient.connectClusterAsync();
```


Pipelining/Batching
-------------------
Lettuce operates using pipelining described in http://redis.io/topics/pipelining.

What is different now? lettuce performs a flush after each command invocation
on the transport. This is fine for the most use cases, but flushing can become
a limiting factor when bulk loading, or you need batching.

Asynchronous connections allow you to disable the auto-flush behavior
and give control over flushing the queued commands:

Example code:

```java
RedisAsyncConnection<String, String> connection = client.connectAsync();
connection.setAutoFlushCommands(false);

connection.set("key", "value");
connection.set("key2", "value2");

connection.flushCommands(); // send the two SET commands out to the transport

connection.setAutoFlushCommands(true);
```

Why on the asynchronous connections only? The asynchronous connections return
already a handle to the result, the synchronous API does not. Adding another API would require to
duplicate all interfaces and increase complexity.
Pipelining/Batching can improve your throughput. Pipelining also works with
Redis Cluster and is not available on pooled connections.

Read more: https://github.com/mp911de/lettuce/wiki/Pipelining-and-command-flushing


Codecs
------
lettuce 3.3 uses a dedicated String codec instance for each String-encoded connection
(Standalone) instead of sharing one String codec for all connections.
Cluster connections share a String codec between the internal connections.

A new `ByteArrayCodec` ships with lettuce 3.3 that allows byte array connections
without the need to create an own codec.


Geo commands
------------
This release supports the Geo-commands of the upcoming Redis 3.2 release.
The Geo-API allows to maintain a set (backed by a Redis sorted set) of Geo
locations described by WGS84 coordinates. You can add and query set members
using the new Geo-API. Use `ZREM` to remove members from the Geo set
until https://github.com/antirez/redis/issues/2674 is resolved.

The design of the Geo-API within lettuce, differs from other APIs in lettuce.
The response structures of `GEORADIUS` depend on the command input, and there
are other languages that fit better into the Redis response structure patterns.
The static type checking within Java would only allow a `List<Object>` (or `Object`)
which contains nested Lists and Maps carrying the data. You would have to cast
the elements to maps or lists and access then again the nested elements.
Working with Lists and Maps in Java is less convenient compared to JavaScript or Ruby.

The Geo-API provides `GeoCoordinates` and `GeoWithin` types that allow direct access to
the response values such as distance or the coordinate points.

Example code:

```java
redis.geoadd(key, 8.6638775, 49.5282537, "Weinheim",
                  8.3796281, 48.9978127, "Office tower",
                  8.665351, 49.553302, "Train station");

Set<String> georadius = redis.georadius(key, 8.6582861, 49.5285695,
                                               5, GeoArgs.Unit.km);

// georadius contains "Weinheim" and "Train station"

Double distance = redis.geodist(key, "Weinheim", "Train station", GeoArgs.Unit.km);

// distance ≈ 2.78km

GeoArgs geoArgs = new GeoArgs().withHash()
                               .withCoordinates()
                               .withDistance()
                               .withCount(1)
                               .desc();

List<GeoWithin<String>> georadiusWithArgs = redis.georadius(key,
                                                        8.665351, 49.553302,
                                                        5, GeoArgs.Unit.km,
                                                        geoArgs);

// georadiusWithArgs contains "Weinheim" and "Train station"
// ordered descending by distance and containing distance/coordinates
```


Command execution reliability
-----------------------------
A new document describes Command execution reliability in the context of lettuce and
reconnects. In general, lettuce supports at-least-once and at-most-once semantics.
The mode of operations is bound to the auto-reconnect flag in the client options.

If auto-reconnect is enabled, at-least-once semantics applies,
at-most-once if auto-reconnect is disabled.

At-least-once and at-most-once are not new to lettuce.
The documentation just explains how these semantics apply to lettuce.

Read more: https://github.com/mp911de/lettuce/wiki/Command-execution-reliability

Enhancements
-----
* Provide access to cluster connection using the advanced cluster API #71
* Cluster connection failover when cluster topology changes #97
* NodeId-bound cluster connections enhancement #104
* Implement at-least-once for cluster connections #105
* Pipelining for lettuce (or: flush after n commands) #92
* Decouple ConnectionWatchdog reconnect from timer thread #100
* Improve performance in 3.3 #90
* Add checks for arguments #69
* Use dedicated string codecs on String connections and add ByteArrayCodec #70
* Tests for command reliability docs #98
* Expose RedisClient.connect(RedisCodec, RedisURI) and RedisClient.connectAsync(RedisCodec, RedisURI) #108
* Add close stale connections and strict cluster member check flags to ClusterClientOptions #109

Commands
-----
* Adopt variadic EXISTS and DEBUG HTSTATS #103
* Support geo commands in lettuce 3.3 #86
* Support NX|XX|CH|INCR options in ZADD #74
* Add support for COUNTKEYSINSLOT #107
* Add support for HSTRLEN #117

Fixes
-----
* Synchronization/cross thread visibility of variables #94
* Check channel state before calling Channel.close.syncUninterruptibly #113
* Stop the HashedWheelTimer before shutting down EventLoopGroups #123

Other
------
* Rework of the wiki


lettuce requires a minimum of Java 8 to build and Java 6 run. It is tested
continuously against Redis 3.0 and the unstable branch

If you need any support, meet lettuce at

* Google Group: https://groups.google.com/d/forum/lettuce-redis-client-users
                or lettuce-redis-client-users@googlegroups.com
* Join the chat at https://gitter.im/mp911de/lettuce
* Github Issues: https://github.com/mp911de/lettuce/issues
* Wiki: https://github.com/mp911de/lettuce/wiki
