package kr.carrotvision.hud;

import android.content.res.Resources;
import android.graphics.*;

/** One neutral vehicle symbol for every detection; no inferred vehicle classification.
 * Source photos are unmodified; canvas silhouette clipping excludes baked checkerboards.
 * Three camera views are sprites, not a reconstructed 3D mesh.
 */
final class DetectedVehicleRenderer {
  private final Bitmap[] sprites;
  private final Path[] silhouettes;
  private final RectF[] bounds;
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
  DetectedVehicleRenderer(Resources r) {
    sprites=new Bitmap[]{BitmapFactory.decodeResource(r,R.drawable.detected_left),
        BitmapFactory.decodeResource(r,R.drawable.detected_center),BitmapFactory.decodeResource(r,R.drawable.detected_right)};
    Path right=polygon(236,568,251,550,305,535,333,535,339,569,397,476,450,428,514,404,645,391,911,391,1068,408,1133,439,1184,482,1260,571,1313,660,1364,711,1390,766,1407,831,1416,933,1406,1057,1396,1155,1374,1198,1343,1206,1231,1206,1214,1178,1205,1120,557,1120,536,1184,514,1207,440,1207,414,1174,403,1007,283,1007,260,990,241,941,232,850,231,740,244,679,278,617,300,604,251,601,237,588);
    Path left=new Path();Matrix mirror=new Matrix();mirror.setScale(-1,1);mirror.postTranslate(1536,0);right.transform(mirror,left);
    Path center=polygon(249,616,257,600,281,591,326,590,337,601,339,631,352,633,378,567,415,490,433,475,525,464,727,462,872,472,903,485,930,523,963,600,984,634,1005,635,1008,601,1024,590,1066,592,1085,607,1091,625,1080,643,1048,649,1024,650,1044,681,1057,729,1067,827,1064,972,1056,1064,1045,1087,1025,1094,972,1094,951,1080,944,1026,397,1026,392,1077,377,1093,316,1093,296,1086,284,1060,278,970,277,818,285,750,301,701,326,662,318,647,281,646,258,639);
    silhouettes=new Path[]{left,center,right};bounds=new RectF[3];
    for(int i=0;i<3;i++){bounds[i]=new RectF();silhouettes[i].computeBounds(bounds[i],true);}
  }
  private static Path polygon(float... xy){Path p=new Path();p.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)p.lineTo(xy[i],xy[i+1]);p.close();return p;}
  void draw(Canvas canvas,float x,float bottom,float width){
    int i=x<688?0:x>848?2:1;
    RectF b=bounds[i];float scale=width/b.width();int save=canvas.save();
    canvas.translate(x-width/2,bottom-b.height()*scale);canvas.scale(scale,scale);canvas.translate(-b.left,-b.top);
    canvas.clipPath(silhouettes[i]);canvas.drawBitmap(sprites[i],0,0,paint);canvas.restoreToCount(save);
  }
}
