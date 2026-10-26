package com.yuddhasphere.utils

import com.yuddhasphere.models.GeoPoint
import com.yuddhasphere.models.Structure
import kotlin.math.*

object GeoMath {
    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Calculates distance in meters between two coordinates using the Haversine formula.
     */
    fun calculateDistanceMeters(p1: GeoPoint, p2: GeoPoint): Double {
        val dLat = Math.toRadians(p2.lat - p1.lat)
        val dLng = Math.toRadians(p2.lng - p1.lng)
        val lat1 = Math.toRadians(p1.lat)
        val lat2 = Math.toRadians(p2.lat)

        val a = sin(dLat / 2).pow(2) + sin(dLng / 2).pow(2) * cos(lat1) * cos(lat2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_METERS * c
    }

    /**
     * Checks if a target location lies within the territory boundary of any active Town Hall or settlement.
     */
    fun isInsideTerritory(target: GeoPoint, structures: List<Structure>, ownerId: String): Boolean {
        for (s in structures) {
            if (s.ownerId != ownerId || !s.online) continue
            val isTerritoryProvider = s.type in setOf("town_hall", "village", "town", "city", "metropolis")
            if (!isTerritoryProvider) continue

            val distance = calculateDistanceMeters(target, s.position)
            if (distance <= s.radiusMeters) {
                return true
            }
        }
        return false
    }

    /**
     * Validates that new placement does not violate minimum separation (default 30m).
     */
    fun satisfiesSeparation(target: GeoPoint, structures: List<Structure>, minSeparationMeters: Double = 30.0): Boolean {
        for (s in structures) {
            val dist = calculateDistanceMeters(target, s.position)
            if (dist < minSeparationMeters) {
                return false
            }
        }
        return true
    }
}
