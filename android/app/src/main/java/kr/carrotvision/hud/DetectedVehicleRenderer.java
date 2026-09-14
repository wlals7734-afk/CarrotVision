package kr.carrotvision.hud;

import android.content.res.Resources;
import android.graphics.*;

/**
 * Original HDA2-inspired neutral symbol for detected traffic.
 * The bridge does not claim a vehicle class, so every valid track uses one
 * deliberately simple tofu/block silhouette instead of guessing car types.
 */
final class DetectedVehicleRenderer {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  DetectedVehicleRenderer(Resources ignored) {
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStrokeCap(Paint.Cap.ROUND);
  }

  void draw(Canvas canvas, float x, float bottom, float width) {
    if (!Float.isFinite(x) || !Float.isFinite(bottom) || !Float.isFinite(width) || width <= 0) return;

    int save = canvas.save();
    canvas.translate(x, bottom);
    canvas.scale(width / 100f, width / 100f);

    float skew = Math.max(-8f, Math.min(8f, (x - 768f) / 75f));

    paint.setStyle(Paint.Style.FILL);
    paint.setShader(new RadialGradient(0, -1, 62,
        new int[]{0x72000000, 0x26000000, 0x00000000},
        new float[]{0f, .58f, 1f}, Shader.TileMode.CLAMP));
    canvas.drawOval(new RectF(-61, -11, 61, 13), paint);
    paint.setShader(null);

    Path body = polygon(
        -49, -57, 49, -57,
        46, -7, 39, 0,
        -39, 0, -46, -7);
    paint.setShader(new LinearGradient(0, -60, 0, 2,
        new int[]{0xffcdd1d4, 0xff999fa5, 0xff626970},
        new float[]{0f, .53f, 1f}, Shader.TileMode.CLAMP));
    canvas.drawPath(body, paint);
    paint.setShader(null);

    Path top = polygon(
        -42, -57,
        -29 + skew, -71,
        29 + skew, -71,
        42, -57,
        31, -48,
        -31, -48);
    paint.setShader(new LinearGradient(-40, -73, 42, -48,
        0xfff3f4f5, 0xffaeb4b9, Shader.TileMode.CLAMP));
    canvas.drawPath(top, paint);
    paint.setShader(null);

    Path glass = polygon(
        -29, -53,
        -22 + skew * .55f, -65,
        22 + skew * .55f, -65,
        29, -53,
        25, -43,
        -25, -43);
    paint.setShader(new LinearGradient(0, -66, 0, -42,
        0xff454c52, 0xff15191d, Shader.TileMode.CLAMP));
    canvas.drawPath(glass, paint);
    paint.setShader(null);

    Path side;
    if (skew >= 0) {
      side = polygon(31, -48, 42, -57, 49, -57, 46, -7, 39, 0, 32, -9);
    } else {
      side = polygon(-31, -48, -42, -57, -49, -57, -46, -7, -39, 0, -32, -9);
    }
    paint.setShader(new LinearGradient(skew >= 0 ? 28 : -49, -45,
        skew >= 0 ? 49 : -28, -8, 0xff858c92, 0xff4e555b, Shader.TileMode.CLAMP));
    canvas.drawPath(side, paint);
    paint.setShader(null);

    paint.setColor(0xff22272b);
    canvas.drawRoundRect(new RectF(-44, -10, -31, 4), 4, 4, paint);
    canvas.drawRoundRect(new RectF(31, -10, 44, 4), 4, 4, paint);
    canvas.drawRoundRect(new RectF(-28, -8, 28, -2), 3, 3, paint);

    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.8f);
    paint.setColor(0xcceff1f2);
    canvas.drawPath(body, paint);
    paint.setStrokeWidth(1.1f);
    paint.setColor(0x99ffffff);
    canvas.drawLine(-40, -57, 40, -57, paint);
    paint.setColor(0x88777e84);
    canvas.drawLine(-36, -1, 36, -1, paint);
    paint.setStyle(Paint.Style.FILL);

    canvas.restoreToCount(save);
  }

  private static Path polygon(float... xy) {
    Path path = new Path();
    path.moveTo(xy[0], xy[1]);
    for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
    path.close();
    return path;
  }
}
