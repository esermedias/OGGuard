package wtf.esuh.ogguard.api;

import wtf.esuh.ogguard.visibility.policy.Cuboid;

import java.util.Map;

/**
 * Service other plugins (for example a base or claim plugin) use to register protected underground regions.
 * Obtain it with {@code Bukkit.getServicesManager().load(GuardApi.class)}. Thread-safe; changes trigger a safe
 * resync for online players on their own region threads.
 */
public interface GuardApi {

    /** Adds or replaces a protected region under a stable key (for example "base:&lt;uuid&gt;"). */
    void putProtectedRegion(String key, Cuboid region);

    /** Removes a protected region; returns true when one was removed. */
    boolean removeProtectedRegion(String key);

    /** Immutable view of runtime-registered regions (config regions are not included). */
    Map<String, Cuboid> protectedRegions();
}
