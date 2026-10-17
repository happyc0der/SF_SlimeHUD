package io.github.schntgaispock.slimehud.waila;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HudRayTraceBoundsTest {
    @Test
    void fullyOwnedQueryPreservesExactRequestedRangeWithOneOwnershipCheck() {
        AtomicInteger checks = new AtomicInteger();
        double distance = HudRayTraceBounds.maximumOwnedDistance(8.5, 8.5, 1, 0, 0, 508,
                (minX, minZ, maxX, maxZ) -> { checks.incrementAndGet(); return true; });
        assertEquals(508, distance);
        assertEquals(1, checks.get());
    }

    @Test
    void reproducedFoliaBoundaryClipsBeforeTheExpandedEntityBoxEntersChunk32() {
        double distance = HudRayTraceBounds.maximumOwnedDistance(8.5, 8.5, 1, 0, 0, 508,
                (minX, minZ, maxX, maxZ) -> minX >= -32 && minZ >= -32 && maxX <= 31 && maxZ <= 31);
        assertEquals(503.3, distance, 0.000001);
        assertTrue(Math.floor(8.5 + distance + 0.2) < 512);
        assertTrue(distance > 8, "Normal nearby HUD targets must remain inside the safe range");
    }

    @Test
    void negativeCoordinatesUseFloorInsteadOfTruncation() {
        double distance = HudRayTraceBounds.maximumOwnedDistance(-8.5, -8.5, -1, 0, 0, 508,
                (minX, minZ, maxX, maxZ) -> minX >= -32 && minZ >= -32 && maxX <= -1 && maxZ <= -1);
        assertEquals(503.3, distance, 0.000001);
        assertTrue(Math.floor(-8.5 - distance - 0.2) >= -512);
    }

    @Test
    void wholeEntitySearchRectangleIsOwnedEvenWhenTheRayMissesAnUnownedCorner() {
        double distance = HudRayTraceBounds.maximumOwnedDistance(8.5, 8.5, 1, 0, 1, 64,
                (minX, minZ, maxX, maxZ) -> !(minX <= 1 && maxX >= 1 && minZ <= 0 && maxZ >= 0));
        assertEquals((16 - 8.5 - 0.2) * Math.sqrt(2), distance, 0.000001);
    }

    @Test
    void itemHitPaddingCanMakeTheOriginUnsafeWithoutAnyForwardTravel() {
        double distance = HudRayTraceBounds.maximumOwnedDistance(15.9, 8.5, -1, 0, 0, 8,
                (minX, minZ, maxX, maxZ) -> minX == 0 && maxX == 0 && minZ == 0 && maxZ == 0);
        assertEquals(0, distance);
    }

    @Test
    void steepAndNonUnitDirectionsKeepTheNativeNormalizedRayShape() {
        double distance = HudRayTraceBounds.maximumOwnedDistance(8.5, 8.5, 3, 4, 0, 1000,
                (minX, minZ, maxX, maxZ) -> minX >= 0 && maxX <= 31 && minZ == 0 && maxZ == 0);
        assertEquals(503.3 / 0.6, distance, 0.000001);
    }

    @Test
    void invalidInputsNeverReachTheOwnershipQuery() {
        HudRayTraceBounds.ChunkOwnership forbidden = (a, b, c, d) -> { throw new AssertionError("Invalid ray queried ownership"); };
        assertEquals(0, HudRayTraceBounds.maximumOwnedDistance(0, 0, 0, 0, 0, 8, forbidden));
        assertEquals(0, HudRayTraceBounds.maximumOwnedDistance(0, 0, 1, 0, 0, Double.POSITIVE_INFINITY, forbidden));
        assertEquals(0, HudRayTraceBounds.maximumOwnedDistance(Double.NaN, 0, 1, 0, 0, 8, forbidden));
        assertEquals(0, HudRayTraceBounds.maximumOwnedDistance(0, 0, 1, 0, 0, -1, forbidden));
    }
}
