package dev.sentinel.bridge;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

final class GeometrySupport {
    private GeometrySupport() {
    }

    static double distanceToBox(Location location, BoundingBox boundingBox) {
        Vector vector = location.toVector();
        double d = GeometrySupport.axisDistance(vector.getX(), boundingBox.getMinX(), boundingBox.getMaxX());
        double d2 = GeometrySupport.axisDistance(vector.getY(), boundingBox.getMinY(), boundingBox.getMaxY());
        double d3 = GeometrySupport.axisDistance(vector.getZ(), boundingBox.getMinZ(), boundingBox.getMaxZ());
        return Math.sqrt(d * d + d2 * d2 + d3 * d3);
    }

    static boolean hasVisibleEntitySample(World world, Location location, BoundingBox boundingBox, double d) {
        Vector vector = location.toVector();
        for (Vector vector2 : GeometrySupport.samples(boundingBox)) {
            Vector vector3 = vector2.clone().subtract(vector);
            double d2 = vector3.length();
            if (d2 <= d) {
                return true;
            }
            if (world.rayTraceBlocks(location, vector3.normalize(), d2 - d, FluidCollisionMode.NEVER, true) != null) continue;
            return true;
        }
        return false;
    }

    static boolean hasVisibleBlockSample(World world, Location location, Block block, BoundingBox boundingBox, double d) {
        Vector vector = location.toVector();
        for (Vector vector2 : GeometrySupport.samples(boundingBox)) {
            Vector vector3 = vector2.clone().subtract(vector);
            double d2 = vector3.length();
            if (d2 <= d) {
                return true;
            }
            RayTraceResult rayTraceResult = world.rayTraceBlocks(location, vector3.normalize(), d2 + d, FluidCollisionMode.NEVER, true);
            if (rayTraceResult != null && !block.equals(rayTraceResult.getHitBlock())) continue;
            return true;
        }
        return false;
    }

    static double minimumLookAngleDegrees(Location location, BoundingBox boundingBox) {
        Vector vector = location.toVector();
        Vector vector2 = location.getDirection().normalize();
        double d = -1.0;
        for (Vector vector3 : GeometrySupport.samples(boundingBox)) {
            Vector vector4 = vector3.clone().subtract(vector);
            if (vector4.length() < 1.0E-6) {
                return 0.0;
            }
            vector4.normalize();
            double d2 = vector2.getX() * vector4.getX() + vector2.getY() * vector4.getY() + vector2.getZ() * vector4.getZ();
            d = Math.max(d, d2);
        }
        d = Math.max(-1.0, Math.min(1.0, d));
        return Math.toDegrees(Math.acos(d));
    }

    private static double axisDistance(double d, double d2, double d3) {
        if (d < d2) {
            return d2 - d;
        }
        if (d > d3) {
            return d - d3;
        }
        return 0.0;
    }

    private static List<Vector> samples(BoundingBox boundingBox) {
        ArrayList<Vector> arrayList = new ArrayList<Vector>(9);
        double d = boundingBox.getMinX();
        double d2 = boundingBox.getMaxX();
        double d3 = boundingBox.getMinY();
        double d4 = boundingBox.getMaxY();
        double d5 = boundingBox.getMinZ();
        double d6 = boundingBox.getMaxZ();
        double d7 = (d + d2) * 0.5;
        double d8 = (d5 + d6) * 0.5;
        double d9 = d3 + (d4 - d3) * 0.18;
        double d10 = d3 + (d4 - d3) * 0.52;
        double d11 = d3 + (d4 - d3) * 0.86;
        double d12 = Math.min(0.12, (d2 - d) * 0.25);
        double d13 = Math.min(0.12, (d6 - d5) * 0.25);
        arrayList.add(new Vector(d7, d10, d8));
        arrayList.add(new Vector(d7, d9, d8));
        arrayList.add(new Vector(d7, d11, d8));
        arrayList.add(new Vector(d + d12, d10, d8));
        arrayList.add(new Vector(d2 - d12, d10, d8));
        arrayList.add(new Vector(d7, d10, d5 + d13));
        arrayList.add(new Vector(d7, d10, d6 - d13));
        arrayList.add(new Vector(d + d12, d11, d5 + d13));
        arrayList.add(new Vector(d2 - d12, d11, d6 - d13));
        return arrayList;
    }
}

