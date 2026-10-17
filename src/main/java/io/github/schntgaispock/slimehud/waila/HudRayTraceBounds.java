package io.github.schntgaispock.slimehud.waila;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

/** Keeps native block and entity ray queries inside the current region's ownership. */
final class HudRayTraceBounds {
    static final double ENTITY_RAY_SIZE = 0.2D;

    private HudRayTraceBounds() {}

    static double maximumOwnedDistance(World world, Location eye, Vector direction, double requestedDistance) {
        return maximumOwnedDistance(eye.getX(), eye.getZ(), direction.getX(), direction.getY(), direction.getZ(),
                requestedDistance,
                (minX, minZ, maxX, maxZ) -> Bukkit.isOwnedByCurrentRegion(world, minX, minZ, maxX, maxZ));
    }

    static double maximumOwnedDistance(double x, double z, double directionX, double directionY, double directionZ,
                                       double requestedDistance, ChunkOwnership ownership) {
        double length = Math.sqrt(directionX * directionX + directionY * directionY + directionZ * directionZ);
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(length) || length == 0
                || !Double.isFinite(requestedDistance) || requestedDistance <= 0) {
            return 0;
        }
        double dx = directionX / length;
        double dz = directionZ / length;
        // Paper/Purpur and ordinary local Folia scans retain the exact configured range.
        if (ownsRay(x, z, dx, dz, requestedDistance, ownership)) {
            return requestedDistance;
        }
        if (!ownsRay(x, z, dx, dz, 0, ownership)) {
            return 0;
        }
        // Only boundary-crossing scans need a search. Keep the known-owned lower bound,
        // so nearby blocks/items remain visible even when a longer configured ray is unsafe.
        double owned = 0;
        double unowned = requestedDistance;
        for (int attempt = 0; attempt < 64; attempt++) {
            double middle = owned + (unowned - owned) / 2;
            if (middle == owned || middle == unowned) {
                break;
            }
            if (ownsRay(x, z, dx, dz, middle, ownership)) {
                owned = middle;
            } else {
                unowned = middle;
            }
        }
        return owned;
    }

    private static boolean ownsRay(double x, double z, double dx, double dz, double distance,
                                   ChunkOwnership ownership) {
        double endX = x + dx * distance;
        double endZ = z + dz * distance;
        // World.rayTraceEntities checks this entire expanded rectangle, not only the
        // line or its endpoint. Include the same item-hit padding before checking chunks.
        double minX = Math.min(x, endX) - ENTITY_RAY_SIZE;
        double minZ = Math.min(z, endZ) - ENTITY_RAY_SIZE;
        double maxX = Math.max(x, endX) + ENTITY_RAY_SIZE;
        double maxZ = Math.max(z, endZ) + ENTITY_RAY_SIZE;
        if (minX < Integer.MIN_VALUE || minZ < Integer.MIN_VALUE
                || maxX > Integer.MAX_VALUE || maxZ > Integer.MAX_VALUE) {
            return false;
        }
        return ownership.owns((int) Math.floor(minX) >> 4, (int) Math.floor(minZ) >> 4,
                (int) Math.floor(maxX) >> 4, (int) Math.floor(maxZ) >> 4);
    }

    @FunctionalInterface
    interface ChunkOwnership {
        boolean owns(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ);
    }
}
