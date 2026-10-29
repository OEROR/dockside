package io.github.dockside.ui.clocks

import java.time.ZoneId

data class City(val zone: String, val name: String, val lat: Double, val lon: Double)

/** Cities offered for the World clock and the world-clock widget. */
object WorldCities {
    val all = listOf(
        City("Pacific/Honolulu", "Honolulu", 21.3, -157.9),
        City("America/Anchorage", "Anchorage", 61.2, -149.9),
        City("America/Los_Angeles", "Los Angeles", 37.3, -122.0),
        City("America/Denver", "Denver", 39.7, -105.0),
        City("America/Chicago", "Chicago", 41.9, -87.6),
        City("America/Mexico_City", "Mexico City", 19.4, -99.1),
        City("America/New_York", "New York", 40.7, -74.0),
        City("America/Toronto", "Toronto", 43.7, -79.4),
        City("America/Bogota", "Bogotá", 4.7, -74.1),
        City("America/Sao_Paulo", "São Paulo", -23.5, -46.6),
        City("America/Argentina/Buenos_Aires", "Buenos Aires", -34.6, -58.4),
        City("Atlantic/Reykjavik", "Reykjavík", 64.1, -21.9),
        City("Europe/London", "London", 51.5, -0.1),
        City("Europe/Lisbon", "Lisbon", 38.7, -9.1),
        City("Europe/Paris", "Paris", 48.9, 2.35),
        City("Europe/Berlin", "Berlin", 52.5, 13.4),
        City("Europe/Rome", "Rome", 41.9, 12.5),
        City("Europe/Madrid", "Madrid", 40.4, -3.7),
        City("Europe/Warsaw", "Warsaw", 52.2, 21.0),
        City("Europe/Kyiv", "Kyiv", 50.45, 30.5),
        City("Europe/Istanbul", "Istanbul", 41.0, 29.0),
        City("Europe/Moscow", "Moscow", 55.75, 37.6),
        City("Africa/Cairo", "Cairo", 30.0, 31.2),
        City("Africa/Lagos", "Lagos", 6.5, 3.4),
        City("Africa/Nairobi", "Nairobi", -1.3, 36.8),
        City("Africa/Johannesburg", "Johannesburg", -26.2, 28.0),
        City("Asia/Dubai", "Dubai", 25.2, 55.3),
        City("Asia/Tehran", "Tehran", 35.7, 51.4),
        City("Asia/Tashkent", "Tashkent", 41.3, 69.3),
        City("Asia/Almaty", "Almaty", 43.2, 76.9),
        City("Asia/Kolkata", "Mumbai", 19.1, 72.9),
        City("Asia/Bangkok", "Bangkok", 13.75, 100.5),
        City("Asia/Singapore", "Singapore", 1.35, 103.8),
        City("Asia/Shanghai", "Beijing", 39.9, 116.4),
        City("Asia/Hong_Kong", "Hong Kong", 22.3, 114.2),
        City("Asia/Seoul", "Seoul", 37.6, 127.0),
        City("Asia/Tokyo", "Tokyo", 35.7, 139.7),
        City("Asia/Vladivostok", "Vladivostok", 43.1, 131.9),
        City("Australia/Perth", "Perth", -31.95, 115.9),
        City("Australia/Sydney", "Sydney", -33.9, 151.2),
        City("Pacific/Auckland", "Auckland", -36.85, 174.8),
    )

    fun byZone(zone: String) = all.firstOrNull { it.zone == zone }

    /** Best guess at where the user is when no location is available. */
    fun guessHome(): City {
        val zone = ZoneId.systemDefault().id
        byZone(zone)?.let { return it }
        val offsetHours = ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds / 3600.0
        return City(zone, zone.substringAfterLast('/').replace('_', ' '), 40.0, offsetHours * 15)
    }
}
