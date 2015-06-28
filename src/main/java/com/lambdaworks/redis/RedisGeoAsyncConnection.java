package com.lambdaworks.redis;

import java.util.List;
import java.util.Set;

/**
 * Asynchronous executed commands for Geo-Commands.
 * 
 * @author <a href="mailto:mpaluch@paluch.biz">Mark Paluch</a>
 * @since 3.3
 * @deprecated Use {@link com.lambdaworks.redis.api.async.RedisGeoAsyncCommands}
 */
@Deprecated
public interface RedisGeoAsyncConnection<K, V> {

    /**
     * Single geo add.
     * 
     * @param key
     * @param longitude
     * @param latitude
     * @param member
     * @return Long integer-reply the number of elements that were added to the set
     */
    RedisFuture<Long> geoadd(K key, double longitude, double latitude, V member);

    /**
     * Multi geo add
     * 
     * @param key
     * @param lonLatMember triplets of double longitude, double latitude and V member
     * @return Long integer-reply the number of elements that were added to the set
     */
    RedisFuture<Long> geoadd(K key, Object... lonLatMember);

    /**
     * Retrieve members selected by distance with the center of {@code longitude} and {@code latitude}.
     * 
     * @param key
     * @param longitude
     * @param latitude
     * @param distance
     * @param unit
     * @return bulk reply
     */
    RedisFuture<Set<V>> georadius(K key, double longitude, double latitude, double distance, GeoArgs.Unit unit);

    /**
     * Retrieve members selected by distance with the center of {@code longitude} and {@code latitude}.
     * 
     * @param key
     * @param longitude
     * @param latitude
     * @param distance
     * @param unit
     * @return
     */
    RedisFuture<List<Object>> georadius(K key, double longitude, double latitude, double distance, GeoArgs.Unit unit,
            GeoArgs geoArgs);

    /**
     * Retrieve members selected by distance with the center of {@code member}.
     * 
     * @param key
     * @param member
     * @param distance
     * @param unit
     * @return
     */
    RedisFuture<Set<V>> georadiusbymember(K key, V member, double distance, GeoArgs.Unit unit);

    /**
     *
     * Retrieve members selected by distance with the center of {@code member}.
     * 
     * @param key
     * @param member
     * @param distance
     * @param unit
     * @return
     */
    RedisFuture<List<Object>> georadiusbymember(K key, V member, double distance, GeoArgs.Unit unit, GeoArgs geoArgs);

    /**
     *
     * Encode latitude and longitude to highest geohash accuracy.
     *
     * @param longitude
     * @param latitude
     * @return nested multi-bulk reply with 1: the 52-bit geohash integer for your latitude longitude, 2: The minimum corner of
     *         your geohash, 3: The maximum corner of your geohash, 4: The averaged center of your geohash.
     */
    RedisFuture<List<Object>> geoencode(double longitude, double latitude);

    /**
     *
     * Encode {@code longitude} and {@code latitude} to highest geohash accuracy.
     *
     * @param longitude
     * @param latitude
     * @param distance
     * @param unit
     * @return nested multi-bulk reply with 1: the 52-bit geohash integer for your latitude longitude, 2: The minimum corner of
     *         your geohash, 3: The maximum corner of your geohash, 4: The averaged center of your geohash.
     */
    RedisFuture<List<Object>> geoencode(double longitude, double latitude, double distance, GeoArgs.Unit unit);

    /**
     *
     * Decode geohash.
     *
     * @param geohash
     * @return nested multi-bulk with 1: minimum decoded corner, 2: maximum decoded corner, 3: averaged center of bounding box.
     */
    RedisFuture<List<Object>> geodecode(long geohash);
}