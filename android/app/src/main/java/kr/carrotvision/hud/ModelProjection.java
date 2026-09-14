package kr.carrotvision.hud;

/** modelV2 coordinates: x forward, y right, z down. */
final class ModelProjection {
  private ModelProjection() {}

  static float screenX(float lateral, float distance) {
    // Android screen X also increases to the right; do not negate model Y.
    return 768 + lateral * 2300 / (distance + 6);
  }
}
