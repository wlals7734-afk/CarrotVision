package kr.carrotvision.hud;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import java.util.*;

final class HudView extends View {
  private final Paint p = new Paint(3);
  private final Bitmap sprite;
  private final ColorMatrixColorFilter gray = new ColorMatrixColorFilter(new ColorMatrix(
      new float[]{.65f,0,0,0,0, 0,.65f,0,0,0, 0,0,.65f,0,0, 0,0,0,1,0}));
  private DriveFrame frame;
  private long received;
  HudView(Context context) {
    super(context);
    sprite = BitmapFactory.decodeResource(getResources(), R.drawable.ev6_white);
    p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    p.setStrokeJoin(Paint.Join.ROUND);
  }
  void setFrame(DriveFrame f) { frame=f; received=SystemClock.elapsedRealtime(); invalidate(); }
  private float sx(float y, float x) { return 500-y*1250/(x+10); }
  private float sy(float x) { return 150+6000/(x+10); }
  private boolean valid(float[] q) { return Float.isFinite(q[0]) && Float.isFinite(q[1]) && q[0]>=0 && q[0]<=150; }
  private void text(Canvas c,String value,float x,float y,float size,int color,Paint.Align align) {
    p.setStyle(Paint.Style.FILL); p.setShader(null); p.setColorFilter(null);
    p.setTextAlign(align); p.setTextSize(size); p.setColor(color); c.drawText(value,x,y,p);
  }
  @Override protected void onDraw(Canvas c) {
    c.drawColor(Color.BLACK);
    float size=Math.min(getWidth(),getHeight());
    if(size<=0) return;
    int saved=c.save(); c.translate((getWidth()-size)/2,(getHeight()-size)/2); c.scale(size/1000,size/1000); c.clipRect(0,0,1000,1000);
    p.setShader(new LinearGradient(0,0,0,1000,0xff111214,0xff070809,Shader.TileMode.CLAMP));
    c.drawRect(0,0,1000,1000,p); p.setShader(null);
    boolean live=frame!=null && SystemClock.elapsedRealtime()-received<1200;
    if(live) {
      drawPath(c);
      for(DriveFrame.Line line:frame.lanes) drawLane(c,line);
      List<DriveFrame.Car> cars=new ArrayList<>(frame.cars);
      Collections.sort(cars,(a,b)->Float.compare(b.x,a.x));
      for(DriveFrame.Car car:cars) {
        if(!Float.isFinite(car.x)||!Float.isFinite(car.y)||car.x<1||car.x>150||car.p<.5f) continue;
        float w=Math.max(28,2500/(car.x+10));
        float x=sx(car.y,car.x), y=sy(car.x);
        drawCar(c,x,y,w,false);
        if(car.source.equals("vision")) {
          p.setColor(0xff25dd65);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);
          c.drawRect(x-w*.53f,y-w*1.02f,x+w*.53f,y+2,p);p.setStyle(Paint.Style.FILL);
          text(c,Math.round(car.x)+" m",x,y+24,20,Color.WHITE,Paint.Align.CENTER);
        }
      }
    }
    drawCar(c,500,775,290,true);
    text(c,live?String.valueOf(Math.round(frame.speed)):"—",48,903,100,Color.WHITE,Paint.Align.LEFT);
    text(c,"km/h",57,946,28,0xffa6a8ac,Paint.Align.LEFT);
    int active=live&&frame.enabled?0xff20dd61:0xff777b80;
    p.setColor(active);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(6);c.drawCircle(909,889,38,p);
    c.drawLine(875,884,943,884,p);c.drawLine(909,891,909,925,p);p.setStyle(Paint.Style.FILL);
    if(!live) {
      text(c,"실시간 데이터 대기",500,290,30,Color.WHITE,Paint.Align.CENTER);
      text(c,"같은 Wi-Fi · 브리지 v2 · UDP 8855",500,326,18,0xffaaaeb3,Paint.Align.CENTER);
    } else {
      text(c,"LIVE",952,40,15,0xff65cd8e,Paint.Align.RIGHT);
      if(frame.leftBlindspot) text(c,"좌측 사각지대",220,710,20,0xffffb547,Paint.Align.CENTER);
      if(frame.rightBlindspot) text(c,"우측 사각지대",780,710,20,0xffffb547,Paint.Align.CENTER);
      if((SystemClock.elapsedRealtime()/500)%2==0) {
        if(frame.leftBlinker) text(c,"◀",290,850,35,0xff20dd61,Paint.Align.CENTER);
        if(frame.rightBlinker) text(c,"▶",710,850,35,0xff20dd61,Paint.Align.CENTER);
      }
    }
    c.restoreToCount(saved);
    postInvalidateDelayed(33);
  }
  private void drawCar(Canvas c,float x,float y,float w,boolean ego) {
    p.setColor(Color.WHITE);p.setColorFilter(ego?null:gray);
    c.drawBitmap(sprite,null,new RectF(x-w/2,y-w,x+w/2,y),p);p.setColorFilter(null);
  }
  private void drawPath(Canvas c) {
    List<float[]> pts=frame.path; if(pts.size()<3) return;
    Path path=new Path(); boolean first=true;
    for(float[] q:pts) if(valid(q)) {
      float x=sx(q[1]+1.0f,q[0]),y=sy(q[0]);
      if(first){path.moveTo(x,y);first=false;}else path.lineTo(x,y);
    }
    if(first)return;
    for(int i=pts.size()-1;i>=0;i--){float[] q=pts.get(i);if(valid(q))path.lineTo(sx(q[1]-1.0f,q[0]),sy(q[0]));}
    path.close();
    p.setShader(new LinearGradient(0,180,0,750,0x0825df65,frame.enabled?0x9925df65:0x33555555,Shader.TileMode.CLAMP));
    c.drawPath(path,p);p.setShader(null);
  }
  private void drawLane(Canvas c,DriveFrame.Line line) {
    if(line.probability<.5f)return;
    p.setColor(0xffdddddf);p.setStyle(Paint.Style.STROKE);
    float[] previous=null;
    for(float[] q:line.points) {
      if(!valid(q))continue;
      if(previous!=null){p.setStrokeWidth(Math.max(1,36/(q[0]+10)));c.drawLine(sx(previous[1],previous[0]),sy(previous[0]),sx(q[1],q[0]),sy(q[0]),p);}
      previous=q;
    }
    p.setStyle(Paint.Style.FILL);
  }
}
