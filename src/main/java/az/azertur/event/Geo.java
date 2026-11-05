package az.azertur.event;

/** Great-circle distance and the box used to pre-filter candidates in SQL. */
final class Geo {
  private static final double EARTH_RADIUS_KM = 6371.0088;
  private static final double KM_PER_DEGREE = Math.PI * EARTH_RADIUS_KM / 180.0;

  record Box(double minLat, double maxLat, double minLng, double maxLng) {}

  private Geo() {}

  static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
    double dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
    double a =
        Math.pow(Math.sin(dLat / 2), 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(dLng / 2), 2);
    return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
  }

  /** A box that contains the whole circle; longitude is left open when it would wrap or at the poles. */
  static Box boundingBox(double lat, double lng, double radiusKm) {
    double dLat = radiusKm / KM_PER_DEGREE;
    double minLat = Math.max(-90, lat - dLat), maxLat = Math.min(90, lat + dLat);
    double cos = Math.cos(Math.toRadians(Math.min(89.9, Math.abs(lat) + dLat)));
    double dLng = cos <= 0.01 ? 360 : radiusKm / (KM_PER_DEGREE * cos);
    if (dLng >= 180 || lng - dLng < -180 || lng + dLng > 180) return new Box(minLat, maxLat, -180, 180);
    return new Box(minLat, maxLat, lng - dLng, lng + dLng);
  }
}
