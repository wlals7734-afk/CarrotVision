package kr.carrotvision.hud;

public final class ModelProjectionCheck {
  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  public static void main(String[] args) {
    for (float d : new float[]{1, 10, 30, 100, 150}) {
      check(ModelProjection.screenX(0, d) == 768, "Straight path stays centered");
      check(ModelProjection.screenX(-1.8f, d) < 768, "Left lane stays left");
      check(ModelProjection.screenX(1.8f, d) > 768, "Right lane stays right");
    }
    // Curves become progressively offset toward their actual direction.
    for (int sign : new int[]{-1, 1}) {
      float previous = 0;
      for (float d : new float[]{10, 20, 40, 60}) {
        float y = sign * .002f * d * d;
        float offset = sign * (ModelProjection.screenX(y, d) - 768);
        check(offset > previous, "Curve direction must not be mirrored");
        previous = offset;
      }
    }
    check(ModelProjection.screenX(2, 10) > ModelProjection.screenX(2, 100),
        "Perspective keeps distant objects closer to center");
    System.out.println("Model projection direction checks passed");
  }
}
