package az.azertur.event;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GeoTest {
  // City centres used by the seed data.
  private static final double[] BAKU = {40.4093, 49.8671}, QUBA = {41.3611, 48.5126};

  @Test
  void distanceMatchesKnownPairs() {
    assertEquals(0, Geo.distanceKm(BAKU[0], BAKU[1], BAKU[0], BAKU[1]), 1e-9);
    // Baku to Quba is about 157 km as the crow flies.
    assertEquals(157, Geo.distanceKm(BAKU[0], BAKU[1], QUBA[0], QUBA[1]), 3);
    // One degree of latitude is about 111.2 km.
    assertEquals(111.2, Geo.distanceKm(0, 0, 1, 0), 0.2);
    // Symmetric.
    assertEquals(
        Geo.distanceKm(BAKU[0], BAKU[1], QUBA[0], QUBA[1]),
        Geo.distanceKm(QUBA[0], QUBA[1], BAKU[0], BAKU[1]),
        1e-9);
  }

  @Test
  void boundingBoxAlwaysContainsTheCircle() {
    for (double radius : new double[] {1, 5, 25, 100, 200}) {
      var box = Geo.boundingBox(BAKU[0], BAKU[1], radius);
      for (int deg = 0; deg < 360; deg += 15) {
        // A point exactly on the circle, in this direction.
        double dLat = Math.cos(Math.toRadians(deg)) * radius / 111.195;
        double dLng = Math.sin(Math.toRadians(deg)) * radius / (111.195 * Math.cos(Math.toRadians(BAKU[0])));
        double lat = BAKU[0] + dLat, lng = BAKU[1] + dLng;
        if (Geo.distanceKm(BAKU[0], BAKU[1], lat, lng) <= radius) {
          assertTrue(lat >= box.minLat() && lat <= box.maxLat(), "lat in box at " + deg + "/" + radius);
          assertTrue(lng >= box.minLng() && lng <= box.maxLng(), "lng in box at " + deg + "/" + radius);
        }
      }
    }
  }

  @Test
  void edgeOfTheRadiusIsDecidedByTheExactDistance() {
    // A point just inside and just outside 25 km north of Baku.
    double inside = BAKU[0] + 24.9 / 111.195, outside = BAKU[0] + 25.1 / 111.195;
    assertTrue(Geo.distanceKm(BAKU[0], BAKU[1], inside, BAKU[1]) <= 25);
    assertTrue(Geo.distanceKm(BAKU[0], BAKU[1], outside, BAKU[1]) > 25);
  }

  @Test
  void boxStaysValidNearThePolesAndTheAntimeridian() {
    var pole = Geo.boundingBox(89.9, 10, 200);
    assertEquals(-180, pole.minLng());
    assertEquals(180, pole.maxLng());
    assertTrue(pole.maxLat() <= 90);
    var wrap = Geo.boundingBox(0, 179.9, 100);
    assertEquals(-180, wrap.minLng());
    assertEquals(180, wrap.maxLng());
  }
}
