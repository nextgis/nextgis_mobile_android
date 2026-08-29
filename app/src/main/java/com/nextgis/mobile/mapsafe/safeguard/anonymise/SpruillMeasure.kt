package com.nextgis.mobile.mapsafe.safeguard.anonymise

import kotlin.math.cos
import kotlin.math.sin

/**
 * Calculates Spruill's disclosure-risk measure for paired original and masked points.
 *
 * A masked point is considered parent-nearest when its corresponding original point is
 * no farther away than every other point in the original dataset. MapSafe also reports
 * the inverse of the disclosure risk as a privacy rating, where a higher value is better.
 *
 * Nearest-neighbour searches use three-dimensional unit-sphere coordinates. Chord
 * distance on a unit sphere is monotonic with great-circle distance, so this finds the
 * same nearest point without distortion from latitude or Web Mercator projection.
 */
object SpruillMeasure {
    const val VERSION = "1.0"

    data class Coordinate(
        val longitude: Double,
        val latitude: Double
    )

    data class Result(
        val evaluatedPoints: Int,
        val parentNearestCount: Int,
        val disclosureRiskPercent: Double,
        val privacyRatingPercent: Double
    )

    fun calculate(
        originalPoints: List<Coordinate>,
        maskedPoints: List<Coordinate>
    ): Result {
        require(originalPoints.isNotEmpty()) {
            "Spruill's measure requires at least one original point."
        }
        require(originalPoints.size == maskedPoints.size) {
            "Spruill's measure requires one masked point for every original point."
        }

        val originalVectors = originalPoints.map(::toUnitVector)
        val spatialIndex = UnitSphereKdTree(originalVectors)
        var parentNearestCount = 0

        maskedPoints.forEachIndexed { index, coordinate ->
            val maskedVector = toUnitVector(coordinate)
            val nearestDistanceSquared = spatialIndex.nearestDistanceSquared(maskedVector)
            val parentDistanceSquared = originalVectors[index].distanceSquared(maskedVector)

            // Equal-distance ties include the parent, matching the reference implementations'
            // comparison of nearest-original distance with parent-original distance.
            if (sameDistance(parentDistanceSquared, nearestDistanceSquared)) {
                parentNearestCount++
            }
        }

        val disclosureRisk = parentNearestCount.toDouble() / originalPoints.size.toDouble() * 100.0
        return Result(
            evaluatedPoints = originalPoints.size,
            parentNearestCount = parentNearestCount,
            disclosureRiskPercent = disclosureRisk,
            privacyRatingPercent = 100.0 - disclosureRisk
        )
    }

    private fun toUnitVector(coordinate: Coordinate): Vector3 {
        require(coordinate.longitude.isFinite() && coordinate.latitude.isFinite()) {
            "Spruill coordinates must be finite numbers."
        }
        require(coordinate.longitude in -180.0..180.0 && coordinate.latitude in -90.0..90.0) {
            "Spruill coordinates must be valid WGS84 longitude and latitude values."
        }

        val longitude = Math.toRadians(coordinate.longitude)
        val latitude = Math.toRadians(coordinate.latitude)
        val latitudeCosine = cos(latitude)
        return Vector3(
            x = latitudeCosine * cos(longitude),
            y = latitudeCosine * sin(longitude),
            z = sin(latitude)
        )
    }

    private fun sameDistance(first: Double, second: Double): Boolean {
        if (first == second) return true
        val tolerance = maxOf(Math.ulp(first), Math.ulp(second)) * 8.0
        return first <= second + tolerance
    }

    private data class Vector3(
        val x: Double,
        val y: Double,
        val z: Double
    ) {
        operator fun get(axis: Int): Double = when (axis) {
            0 -> x
            1 -> y
            else -> z
        }

        fun distanceSquared(other: Vector3): Double {
            val deltaX = x - other.x
            val deltaY = y - other.y
            val deltaZ = z - other.z
            return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
        }
    }

    private class UnitSphereKdTree(points: List<Vector3>) {
        private data class Node(
            val point: Vector3,
            val axis: Int,
            val lower: Node?,
            val upper: Node?
        )

        private val root = build(points, depth = 0)

        fun nearestDistanceSquared(query: Vector3): Double {
            return search(root, query, Double.POSITIVE_INFINITY)
        }

        private fun search(node: Node?, query: Vector3, currentBest: Double): Double {
            if (node == null) return currentBest

            var best = minOf(currentBest, node.point.distanceSquared(query))
            val axisDifference = query[node.axis] - node.point[node.axis]
            val nearBranch = if (axisDifference <= 0.0) node.lower else node.upper
            val farBranch = if (axisDifference <= 0.0) node.upper else node.lower

            best = search(nearBranch, query, best)
            if (axisDifference * axisDifference <= best) {
                best = search(farBranch, query, best)
            }
            return best
        }

        private fun build(points: List<Vector3>, depth: Int): Node? {
            if (points.isEmpty()) return null
            val axis = depth % 3
            val sorted = points.sortedBy { it[axis] }
            val middle = sorted.size / 2
            return Node(
                point = sorted[middle],
                axis = axis,
                lower = build(sorted.subList(0, middle), depth + 1),
                upper = build(sorted.subList(middle + 1, sorted.size), depth + 1)
            )
        }
    }
}
