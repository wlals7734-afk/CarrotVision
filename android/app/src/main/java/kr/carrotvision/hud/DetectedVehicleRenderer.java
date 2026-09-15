package kr.carrotvision.hud;

import android.content.res.Resources;
import android.graphics.*;

/**
 * Draws the user's three supplied HDA2-style vehicle assets.
 * Bitmaps are decoded once and then scaled by the GPU for low-end hardware.
 */
final class DetectedVehicleRenderer {
  private final Paint paint =
      new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
  private final RectF destination = new RectF();
  private final Bitmap front;
  private final Bitmap right;
  private final Bitmap left;

  DetectedVehicleRenderer(Resources resources) {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inScaled = false;
    front = BitmapFactory.decodeResource(resources, R.drawable.detected_center, options);
    right = BitmapFactory.decodeResource(resources, R.drawable.detected_right, options);
    left = BitmapFactory.decodeResource(resources, R.drawable.detected_left, options);
  }

  void draw(Canvas canvas, float x, float bottom, float width) {
    if (!Float.isFinite(x) || !Float.isFinite(bottom) || !Float.isFinite(width) || width <= 0) return;

    Bitmap bitmap = x < 620f ? left : x > 916f ? right : front;
    float height = width * bitmap.getHeight() / bitmap.getWidth();
    destination.set(x - width * .5f, bottom - height, x + width * .5f, bottom);
    canvas.drawBitmap(bitmap, null, destination, paint);
  }
}
