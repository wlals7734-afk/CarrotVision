package kr.carrotvision.hud;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import java.util.*;

final class HudView extends View {
  private final Paint p = new Paint(3);
  private final Bitmap reference;
  private final SharedPreferences preferences;
  private final String[] colors={"원본 차콜","화이트","실버","블랙","블루","레드"};
  private final int[] paints={0,0xffe5e8ea,0xffa0a6ad,0xff15171a,0xff245c9c,0xffa62e35};
  private int colorIndex;
  private DriveFrame frame;
  private long received;
  private float touchX,touchY;
  HudView(Context context) {
    super(context);
    reference=BitmapFactory.decodeResource(getResources(),R.drawable.reference_ui);
    preferences=context.getSharedPreferences("appearance",Context.MODE_PRIVATE);
    colorIndex=Math.max(0,Math.min(paints.length-1,preferences.getInt("carColor",1)));
    setContentDescription("CarrotVision. 내 차량을 길게 눌러 색상 변경");
    setOnLongClickListener(v->{
      if(touchX<520||touchX>1010||touchY<750||touchY>1190)return false;
      if(live()&&frame.speed>1){Toast.makeText(context,"정차 후 색상을 변경해 주세요",Toast.LENGTH_SHORT).show();return true;}
      new AlertDialog.Builder(context).setTitle("내 차량 색상")
        .setSingleChoiceItems(colors,colorIndex,(dialog,which)->{
          colorIndex=which;preferences.edit().putInt("carColor",which).apply();invalidate();dialog.dismiss();
        }).setNegativeButton("닫기",null).show();
      return true;
    });
    p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
  }
  @Override public boolean onTouchEvent(MotionEvent e){
    float size=Math.min(getWidth(),getHeight());
    if(e.getAction()==MotionEvent.ACTION_DOWN&&size>0){
      touchX=(e.getX()-(getWidth()-size)/2)*1536/size;
      touchY=(e.getY()-(getHeight()-size)/2)*1536/size;
    }
    return super.onTouchEvent(e);
  }
  void setFrame(DriveFrame f){frame=f;received=SystemClock.elapsedRealtime();invalidate();}
  private boolean live(){return frame!=null&&SystemClock.elapsedRealtime()-received<1200;}
  private float sx(float lateral,float distance){return 768-lateral*2300/(distance+6);}
  private float sy(float distance){return 250+5580/(distance+6);}
  private boolean valid(float[] q){return Float.isFinite(q[0])&&Float.isFinite(q[1])&&q[0]>=0&&q[0]<=150;}
  private Path polygon(float... xy){
    Path a=new Path();a.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)a.lineTo(xy[i],xy[i+1]);a.close();return a;
  }
  private void text(Canvas c,String s,float x,float y,float size,int color,Paint.Align align){
    p.setShader(null);p.setColorFilter(null);p.setStyle(Paint.Style.FILL);p.setColor(color);
    p.setTextSize(size);p.setTextAlign(align);c.drawText(s,x,y,p);
  }
  @Override protected void onDraw(Canvas c){
    c.drawColor(Color.BLACK);float size=Math.min(getWidth(),getHeight());if(size<=0)return;
    int save=c.save();c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/1536,size/1536);
    c.clipRect(0,0,1536,1536);
    p.setColor(Color.WHITE);p.setShader(new RadialGradient(768,780,1000,new int[]{0xff252627,0xff101112,0xff0b0c0d},new float[]{0,.72f,1},Shader.TileMode.CLAMP));
    c.drawRect(0,0,1536,1536,p);p.setShader(null);
    drawRoad(c);
    boolean connected=live();
    if(connected){
      drawPath(c);
      for(DriveFrame.Line line:frame.lanes)drawLane(c,line);
      List<DriveFrame.Car> cars=new ArrayList<>(frame.cars);
      Collections.sort(cars,(a,b)->Float.compare(b.x,a.x));
      for(DriveFrame.Car car:cars){
        if(!Float.isFinite(car.x)||!Float.isFinite(car.y)||car.x<1||car.x>150||car.p<.5f)continue;
        float w=Math.min(310,5000/(car.x+11.5f)),x=sx(car.y,car.x),y=sy(car.x);
        drawLead(c,x,y,w);
        p.setColor(0xff23ce4e);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);
        c.drawRect(x-w*.56f,y-w*.93f,x+w*.56f,y+5,p);p.setStyle(Paint.Style.FILL);
        text(c,Math.round(car.x)+" m",x,y+46,32,Color.WHITE,Paint.Align.CENTER);
      }
    }
    drawEgo(c);
    text(c,connected?String.valueOf(Math.round(frame.speed)):"—",80,1390,148,Color.WHITE,Paint.Align.LEFT);
    text(c,"km/h",90,1450,49,0xff999b9d,Paint.Align.LEFT);
    drawWheel(c,connected&&frame.enabled);
    text(c,connected?"LIVE":"연결 대기",1450,48,18,connected?0xff65cd8e:0xffa0a0a0,Paint.Align.RIGHT);
    if(!connected)text(c,"실시간 데이터 대기",768,360,28,0xffb0b2b5,Paint.Align.CENTER);
    if(connected){
      if(frame.leftBlindspot)text(c,"좌측 사각지대",340,750,26,0xffffb547,Paint.Align.CENTER);
      if(frame.rightBlindspot)text(c,"우측 사각지대",1196,750,26,0xffffb547,Paint.Align.CENTER);
      if((SystemClock.elapsedRealtime()/500)%2==0){
        if(frame.leftBlinker)text(c,"◀",485,990,40,0xff20dd61,Paint.Align.CENTER);
        if(frame.rightBlinker)text(c,"▶",1051,990,40,0xff20dd61,Paint.Align.CENTER);
      }
    }
    // The underlying straight road is a decorative perspective grid, not detected lanes.
    text(c,"내 차 길게 누르기 · 색상",768,1498,18,0xff65686b,Paint.Align.CENTER);
    c.restoreToCount(save);postInvalidateDelayed(33);
  }
  private void drawRoad(Canvas c){
    p.setShader(new LinearGradient(0,250,0,1300,new int[]{0x00ffffff,0xffdddddd,0xffdddddd,0x00ffffff},new float[]{0,.23f,.82f,1},Shader.TileMode.CLAMP));
    c.drawPath(polygon(602,250,0,546,0,558),p);
    c.drawPath(polygon(934,250,1536,546,1536,558),p);
    for(int side:new int[]{-1,1}){
      for(float d=2;d<120;d+=7){
        float a=sy(d),b=sy(d+3.6f);
        float xa=sx(side*1.8f,d),xb=sx(side*1.8f,d+3.6f);
        float wa=65/(d+6),wb=65/(d+9.6f);
        c.drawPath(polygon(xa-wa,a,xa+wa,a,xb+wb,b,xb-wb,b),p);
      }
    }
    p.setShader(null);
  }
  private void drawPath(Canvas c){
    if(frame.path.size()<3)return;
    Path a=new Path();boolean first=true;
    for(float[] q:frame.path)if(valid(q)){float x=sx(q[1]+.95f,q[0]),y=sy(q[0]);if(first){a.moveTo(x,y);first=false;}else a.lineTo(x,y);}
    if(first)return;
    for(int i=frame.path.size()-1;i>=0;i--){float[] q=frame.path.get(i);if(valid(q))a.lineTo(sx(q[1]-.95f,q[0]),sy(q[0]));}
    a.close();p.setShader(new LinearGradient(0,390,0,1000,0x0024da5c,0xbb24da5c,Shader.TileMode.CLAMP));c.drawPath(a,p);p.setShader(null);
  }
  private void drawLane(Canvas c,DriveFrame.Line line){
    if(line.probability<.5f)return;
    p.setColor(0xbbe5e7e8);p.setStyle(Paint.Style.STROKE);float[] last=null;
    for(float[] q:line.points)if(valid(q)){
      if(last!=null){p.setStrokeWidth(Math.max(1,50/(q[0]+6)));c.drawLine(sx(last[1],last[0]),sy(last[0]),sx(q[1],q[0]),sy(q[0]),p);}last=q;
    }
    p.setStyle(Paint.Style.FILL);
  }
  private void drawLead(Canvas c,float x,float y,float w){
    int s=c.save();c.translate(x-w/2,y-w*.82f);c.scale(w/112,w/112);c.translate(-713,-317);
    c.clipPath(polygon(728,321,744,317,799,318,810,326,816,343,823,348,822,388,817,399,718,399,713,391,714,349,721,341));
    p.setColor(Color.WHITE);c.drawBitmap(reference,0,0,p);c.restoreToCount(s);
  }
  private void drawEgo(Canvas c){
    int s=c.save();
    Path silhouette=polygon(550,826,569,818,593,823,614,791,635,776,693,769,832,770,894,780,916,816,922,828,945,819,976,827,978,836,962,846,941,844,962,901,983,975,987,1060,976,1146,965,1167,932,1168,917,1154,588,1154,578,1167,550,1161,543,1122,541,1027,552,966,572,903,590,844,564,847,552,843);
    c.clipPath(silhouette);p.setColor(Color.WHITE);c.drawBitmap(reference,0,0,p);
    if(colorIndex!=0){
      int body=c.save();
      Path mask=polygon(598,821,632,779,691,772,831,774,891,782,919,833,906,856,883,846,651,845,629,858);
      mask.addPath(polygon(594,850,613,864,625,929,613,975,564,994,555,972,578,896));
      mask.addPath(polygon(913,851,930,850,960,915,978,975,952,995,918,975,907,928));
      mask.addPath(polygon(619,960,910,960,921,978,609,978));
      mask.addPath(polygon(564,1013,588,1023,945,1023,969,1011,961,1055,927,1078,606,1079,568,1056));
      mask.addPath(polygon(571,1080,605,1090,929,1090,961,1078,958,1102,923,1118,607,1118,576,1106));
      c.clipPath(mask);
      int col=paints[colorIndex];
      // Repaint body panels while preserving source shading, glass, tyres and red lights.
      float lift=colorIndex==1?125:colorIndex==2?65:colorIndex==3?-15:15;
      float r=Color.red(col)/255f,g=Color.green(col)/255f,b=Color.blue(col)/255f;
      p.setColorFilter(new ColorMatrixColorFilter(new float[]{r,0,0,0,lift*r,0,g,0,0,lift*g,0,0,b,0,lift*b,0,0,0,1,0}));
      c.drawBitmap(reference,0,0,p);p.setColorFilter(null);c.restoreToCount(body);
    }
    c.restoreToCount(s);
  }
  private void drawWheel(Canvas c,boolean enabled){
    p.setColor(enabled?0xff13df4e:0xff73777b);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(10);
    c.drawCircle(1388,1355,62,p);p.setStyle(Paint.Style.FILL);
    Path a=new Path();a.moveTo(1330,1344);a.cubicTo(1367,1319,1409,1319,1446,1344);a.lineTo(1446,1363);
    a.cubicTo(1415,1350,1394,1380,1394,1416);a.lineTo(1382,1416);a.cubicTo(1382,1380,1361,1350,1330,1363);a.close();c.drawPath(a,p);
  }
}
