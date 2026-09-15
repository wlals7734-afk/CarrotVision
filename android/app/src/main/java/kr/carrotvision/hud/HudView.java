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
  private static final float DESIGN_WIDTH = 1536f;
  private static final float DESIGN_HEIGHT = 1920f;
  private static final float WORLD_Y_OFFSET = 180f;
  private final Paint p = new Paint(3);
  private final Shader backgroundShader = new RadialGradient(768,960,1200,
      new int[]{0xff252627,0xff101112,0xff0b0c0d},
      new float[]{0,.72f,1},Shader.TileMode.CLAMP);
  private final Shader pathShader = new LinearGradient(0,390,0,1000,
      0x0024da5c,0xbb24da5c,Shader.TileMode.CLAMP);
  private final Path drivingPath = new Path();
  private final ArrayList<DriveFrame.Car> sortedCars = new ArrayList<>();
  private static final Comparator<DriveFrame.Car> FAR_TO_NEAR =
      (a,b)->Float.compare(b.x,a.x);
  private static final long RENDER_INTERVAL_MS = 33L;
  private static final long MIN_INTERPOLATION_MS = 33L;
  private static final long MAX_INTERPOLATION_MS = 80L;
  private DriveFrame previousFrame;
  private long previousReceived;
  private boolean renderLoopRunning;
  private float shapeYCorrection=1f;
  private final Runnable renderTick = new Runnable() {
    @Override public void run() {
      if(live()) {
        invalidate();
        postDelayed(this,RENDER_INTERVAL_MS);
      } else {
        invalidate();
        renderLoopRunning=false;
      }
    }
  };
  private final Bitmap reference;
  private final DetectedVehicleRenderer detectedVehicles;
  private final SharedPreferences preferences;
  private final String[] colors={"차콜","화이트","실버","블랙","블루","레드"};
  private final int[] paints={0,0xffe5e8ea,0xffa0a6ad,0xff15171a,0xff245c9c,0xffa62e35};
  private int colorIndex;
  private DriveFrame frame;
  private final LaneTrack[] laneTracks={new LaneTrack(),new LaneTrack(),new LaneTrack(),new LaneTrack()};
  private long received;
  private long indicatorStart;
  private int indicatorMask;
  private float brakeGlow;
  private float touchX,touchY;

  HudView(Context context) {
    super(context);
    reference=BitmapFactory.decodeResource(getResources(),R.drawable.ego_ev6_user);
    detectedVehicles=new DetectedVehicleRenderer(getResources());
    preferences=context.getSharedPreferences("appearance",Context.MODE_PRIVATE);
    colorIndex=Math.max(0,Math.min(paints.length-1,preferences.getInt("ev6CarColor",1)));
    setContentDescription("CarrotVision. 내 차량을 길게 눌러 색상 변경");
    setOnLongClickListener(v->{
      if(touchX<520||touchX>1010||touchY<930||touchY>1370)return false;
      if(live()&&frame.speed>1){Toast.makeText(context,"정차 후 색상을 변경해 주세요",Toast.LENGTH_SHORT).show();return true;}
      new AlertDialog.Builder(context).setTitle("내 차량 색상")
        .setSingleChoiceItems(colors,colorIndex,(dialog,which)->{
          colorIndex=which;preferences.edit().putInt("ev6CarColor",which).apply();invalidate();dialog.dismiss();
        }).setNegativeButton("닫기",null).show();
      return true;
    });
    p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
  }

  @Override public boolean onTouchEvent(MotionEvent e){
    float scaleX=getWidth()/DESIGN_WIDTH;
    float scaleY=getHeight()/DESIGN_HEIGHT;
    if(e.getAction()==MotionEvent.ACTION_DOWN&&scaleX>0&&scaleY>0){
      touchX=e.getX()/scaleX;
      touchY=e.getY()/scaleY;
    }
    return super.onTouchEvent(e);
  }

  void setFrame(DriveFrame f){
    long now=SystemClock.elapsedRealtime();int next=(f.leftBlinker?1:0)|(f.rightBlinker?2:0);
    if(next!=indicatorMask || now-received>=1200)indicatorStart=now;
    indicatorMask=next;previousFrame=frame;previousReceived=received;frame=f;received=now;
    for(int i=0;i<laneTracks.length;i++){
      DriveFrame.Line line=i<f.lanes.size()?f.lanes.get(i):null;
      laneTracks[i].update(line==null?0f:line.probability,line==null?null:line.points,now);
    }
    if(!renderLoopRunning){
      renderLoopRunning=true;
      post(renderTick);
    }
  }

  private boolean live(){return frame!=null&&SystemClock.elapsedRealtime()-received<1200;}
  private float sx(float lateral,float distance){return ModelProjection.screenX(lateral,distance);}
  private float sy(float distance){return 250+5580/(distance+6);}
  private boolean valid(float[] q){return Float.isFinite(q[0])&&Float.isFinite(q[1])&&q[0]>=0&&q[0]<=150;}
  private Path polygon(float... xy){
    Path a=new Path();a.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)a.lineTo(xy[i],xy[i+1]);a.close();return a;
  }
  private void text(Canvas c,String s,float x,float y,float size,int color,Paint.Align align){
    p.setShader(null);p.setColorFilter(null);p.setStyle(Paint.Style.FILL);p.setColor(color);
    p.setTextSize(size);p.setTextAlign(align);c.drawText(s,x,y,p);
  }
  private int preserveAspect(Canvas c,float pivotX,float pivotY){
    int save=c.save();
    c.translate(pivotX,pivotY);
    c.scale(1f,shapeYCorrection);
    c.translate(-pivotX,-pivotY);
    return save;
  }

  @Override protected void onDraw(Canvas c){
    long drawNow=SystemClock.elapsedRealtime();
    float scaleX=getWidth()/DESIGN_WIDTH;
    float scaleY=getHeight()/DESIGN_HEIGHT;
    if(scaleX<=0||scaleY<=0)return;
    shapeYCorrection=scaleX/scaleY;

    c.drawColor(0xff0b0c0d);
    int save=c.save();
    c.scale(scaleX,scaleY);
    c.clipRect(0,0,DESIGN_WIDTH,DESIGN_HEIGHT);
    p.setColor(Color.WHITE);p.setShader(backgroundShader);
    c.drawRect(0,0,DESIGN_WIDTH,DESIGN_HEIGHT,p);p.setShader(null);

    boolean connected=live();
    int worldSave=c.save();c.translate(0,WORLD_Y_OFFSET);
    if(connected)drawPath(c);
    for(LaneTrack lane:laneTracks)drawLane(c,lane,drawNow);
    if(connected){
      sortedCars.clear();
      sortedCars.addAll(frame.cars);
      Collections.sort(sortedCars,FAR_TO_NEAR);
      int first=Math.max(0,sortedCars.size()-8);
      float blend=interpolationBlend(drawNow);
      for(int i=first;i<sortedCars.size();i++){
        DriveFrame.Car car=sortedCars.get(i);
        if(!Float.isFinite(car.x)||!Float.isFinite(car.y)||car.x<1||car.x>150||car.p<.5f)continue;
        DriveFrame.Car old=previousCar(car);
        float distance=old==null?car.x:lerp(old.x,car.x,blend);
        float lateral=old==null?car.y:lerp(old.y,car.y,blend);
        float w=Math.min(310,5000/(distance+11.5f)),x=sx(lateral,distance),y=sy(distance);
        int vehicleSave=preserveAspect(c,x,y);
        detectedVehicles.draw(c,x,y,w);
        c.restoreToCount(vehicleSave);
      }
    }
    int egoSave=preserveAspect(c,768,1190);
    drawEgo(c);
    c.restoreToCount(egoSave);
    c.restoreToCount(worldSave);

    if(connected && (frame.trafficState==1 || frame.trafficState==2)){
      int signalSave=preserveAspect(c,1315,149);
      drawTrafficLight(c,frame.trafficState);
      c.restoreToCount(signalSave);
    }

    int speedSave=preserveAspect(c,80,1740);
    text(c,connected?String.valueOf(Math.round(frame.speed)):"—",80,1740,148,Color.WHITE,Paint.Align.LEFT);
    c.restoreToCount(speedSave);
    int unitSave=preserveAspect(c,90,1800);
    text(c,"km/h",90,1800,49,0xff999b9d,Paint.Align.LEFT);
    c.restoreToCount(unitSave);

    int wheelAspectSave=preserveAspect(c,1388,1695);
    int wheelSave=c.save();c.translate(0,340);drawWheel(c,connected&&frame.enabled);c.restoreToCount(wheelSave);
    c.restoreToCount(wheelAspectSave);

    int liveSave=preserveAspect(c,1450,48);
    text(c,connected?"LIVE":"연결 대기",1450,48,18,connected?0xff65cd8e:0xffa0a0a0,Paint.Align.RIGHT);
    c.restoreToCount(liveSave);

    if(!connected){
      int waitSave=preserveAspect(c,768,360);
      text(c,"실시간 데이터 대기",768,360,28,0xffb0b2b5,Paint.Align.CENTER);
      c.restoreToCount(waitSave);
    }
    if(connected){
      if(frame.leftBlindspot){
        int bs=preserveAspect(c,340,930);text(c,"좌측 사각지대",340,930,26,0xffffb547,Paint.Align.CENTER);c.restoreToCount(bs);
      }
      if(frame.rightBlindspot){
        int bs=preserveAspect(c,1196,930);text(c,"우측 사각지대",1196,930,26,0xffffb547,Paint.Align.CENTER);c.restoreToCount(bs);
      }
      if((SystemClock.elapsedRealtime()/500)%2==0){
        if(frame.leftBlinker){int b=preserveAspect(c,485,1170);text(c,"◀",485,1170,40,0xff20dd61,Paint.Align.CENTER);c.restoreToCount(b);}
        if(frame.rightBlinker){int b=preserveAspect(c,1051,1170);text(c,"▶",1051,1170,40,0xff20dd61,Paint.Align.CENTER);c.restoreToCount(b);}
      }
    }
    int hintSave=preserveAspect(c,768,1870);
    text(c,"내 차 길게 누르기 · 색상",768,1870,18,0xff65686b,Paint.Align.CENTER);
    c.restoreToCount(hintSave);
    c.restoreToCount(save);
  }

  private float interpolationBlend(long now){
    if(previousFrame==null || previousReceived<=0 || received<=previousReceived)return 1f;
    long interval=Math.max(MIN_INTERPOLATION_MS,Math.min(MAX_INTERPOLATION_MS,received-previousReceived));
    return Math.max(0f,Math.min(1f,(now-received)/(float)interval));
  }
  private DriveFrame.Car previousCar(DriveFrame.Car current){
    if(previousFrame==null)return null;
    DriveFrame.Car best=null;float bestScore=Float.MAX_VALUE;
    for(DriveFrame.Car candidate:previousFrame.cars){
      if(candidate==null || !Float.isFinite(candidate.x) || !Float.isFinite(candidate.y))continue;
      if(current.source!=null && candidate.source!=null && !current.source.equals(candidate.source))continue;
      float dx=Math.abs(candidate.x-current.x),dy=Math.abs(candidate.y-current.y);
      if(dx>20f || dy>4f)continue;
      float score=dx+dy*4f;
      if(score<bestScore){bestScore=score;best=candidate;}
    }
    return best;
  }
  private static float lerp(float from,float to,float amount){return from+(to-from)*amount;}
  @Override protected void onDetachedFromWindow(){
    removeCallbacks(renderTick);renderLoopRunning=false;super.onDetachedFromWindow();
  }

  private void drawTrafficLight(Canvas c,int state){
    if(state!=1 && state!=2)return;
    p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(0xff303438);
    c.drawRoundRect(1160,90,1470,208,42,42,p);
    p.setColor(0xff121416);c.drawRoundRect(1164,94,1466,204,39,39,p);
    int[] colors={0xffff3434,0xffffba28,0xff27e765};
    for(int i=0;i<3;i++){
      float x=1217+i*98,y=149;
      boolean on=(i==0&&state==1)||(i==2&&state==2);
      p.setColor(0xff08090a);c.drawCircle(x,y,43,p);
      p.setShader(new RadialGradient(x-8,y-10,48,
        on?new int[]{Color.WHITE,colors[i],0xff171a1c}:new int[]{0xff303438,0xff202326,0xff111315},
        new float[]{0,.35f,1},Shader.TileMode.CLAMP));
      c.drawCircle(x,y,35,p);p.setShader(null);
      p.setColor(on?0xaaffffff:0xff34383b);
      for(int row=-3;row<=3;row++)for(int col=-3;col<=3;col++)
        if(row*row+col*col<=10)c.drawCircle(x+col*8,y+row*8,1.7f,p);
    }
    text(c,"당근 판단 · "+(state==1?"정지":state==2?"진행":"대기"),1315,241,24,0xffbdc2c6,Paint.Align.CENTER);
  }

  private void drawPath(Canvas c){
    if(frame.path.size()<3)return;
    Path a=drivingPath;a.rewind();boolean first=true;
    for(float[] q:frame.path)if(valid(q)){float x=sx(q[1]+.95f,q[0]),y=sy(q[0]);if(first){a.moveTo(x,y);first=false;}else a.lineTo(x,y);}
    if(first)return;
    for(int i=frame.path.size()-1;i>=0;i--){float[] q=frame.path.get(i);if(valid(q))a.lineTo(sx(q[1]-.95f,q[0]),sy(q[0]));}
    a.close();p.setShader(pathShader);c.drawPath(a,p);p.setShader(null);
  }

  private void drawLane(Canvas c,LaneTrack lane,long now){
    float opacity=lane.alpha(now);if(opacity<=0 || lane.points.size()<2)return;
    p.setShader(null);p.setColorFilter(null);p.setColor(0xffe5e7e8);
    p.setAlpha(Math.round(220*opacity));p.setStyle(Paint.Style.STROKE);
    p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
    float[] last=null;
    for(float[] q:lane.points){
      if(last!=null){p.setStrokeWidth(Math.max(1,50/(q[0]+6)));c.drawLine(sx(last[1],last[0]),sy(last[0]),sx(q[1],q[0]),sy(q[0]),p);}last=q;
    }
    p.setAlpha(255);p.setStrokeCap(Paint.Cap.BUTT);p.setStyle(Paint.Style.FILL);
  }

  private void drawEgo(Canvas c){
    int save=c.save();
    float scale=440f/1448f;
    c.translate(548,1168-1370*scale);c.scale(scale,scale);c.translate(-44,-84);
    Path silhouette=polygon(64,295,82,278,135,260,190,251,211,259,225,276,219,308,
      249,275,290,207,344,136,373,113,414,103,578,88,768,84,959,88,1122,103,
      1164,113,1193,136,1246,207,1287,275,1317,308,1311,276,1325,259,1346,251,
      1401,260,1454,278,1472,295,1470,336,1457,351,1425,359,1358,361,1346,356,
      1376,490,1416,617,1450,678,1470,747,1483,837,1488,1040,1492,1265,
      1482,1403,1467,1436,1433,1453,1347,1453,1320,1438,1289,1391,
      1129,1390,960,1402,768,1403,576,1402,407,1390,247,1391,
      216,1438,189,1453,103,1453,69,1436,54,1403,44,1265,48,1040,
      53,837,66,747,86,678,120,617,160,490,190,356,178,361,111,359,79,351,66,336);
    c.clipPath(silhouette);p.setColor(Color.WHITE);p.setShader(null);p.setStyle(Paint.Style.FILL);
    p.setColorFilter(null);c.drawBitmap(reference,0,0,p);
    if(colorIndex!=1){
      int body=c.save();
      Path panels=polygon(414,227,590,203,946,203,1122,227,1130,365,1190,385,
        1240,442,1215,477,321,477,296,442,346,385,406,365);
      panels.addPath(polygon(296,840,1240,840,1230,1034,1200,1107,336,1107,306,1034));
      panels.addPath(polygon(182,365,218,367,287,487,270,724,250,798,64,851,86,703,128,612));
      panels.addPath(polygon(1354,365,1318,367,1249,487,1266,724,1286,798,1472,851,1450,703,1408,612));
      c.clipPath(panels);
      int color=colorIndex==0?0xff454950:paints[colorIndex];
      p.setColorFilter(new LightingColorFilter(color,0));c.drawBitmap(reference,0,0,p);
      p.setColorFilter(null);c.restoreToCount(body);
    }
    drawEgoLights(c);
    c.restoreToCount(save);
  }

  private void drawEgoLights(Canvas c){
    boolean fresh=live();
    float target=fresh && frame.brakeLights?1f:0f;
    brakeGlow=fresh?brakeGlow+(target-brakeGlow)*.45f:0f;
    if(brakeGlow>.01f){
      Path rear=new Path();rear.moveTo(76,868);rear.cubicTo(155,823,228,817,300,817);
      rear.lineTo(1236,817);rear.cubicTo(1308,817,1381,823,1460,868);
      lamp(c,rear,0xffff2424,brakeGlow,18);
      Path high=new Path();high.moveTo(475,488);high.lineTo(1060,488);
      lamp(c,high,0xffff2424,brakeGlow,8);
    }
    if(fresh && (SystemClock.elapsedRealtime()-indicatorStart)%900<450){
      if(frame.leftBlinker){Path left=new Path();left.moveTo(81,866);left.cubicTo(147,831,220,817,279,819);lamp(c,left,0xffffa800,1f,24);}
      if(frame.rightBlinker){Path right=new Path();right.moveTo(1257,819);right.cubicTo(1316,817,1389,831,1455,866);lamp(c,right,0xffffa800,1f,24);}
    }
  }

  private void lamp(Canvas c,Path shape,int color,float intensity,float width){
    p.setColorFilter(null);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);
    p.setColor(color);p.setAlpha(Math.round(38*intensity));p.setStrokeWidth(width*3);c.drawPath(shape,p);
    p.setAlpha(Math.round(240*intensity));p.setStrokeWidth(width);c.drawPath(shape,p);
    p.setColor(Color.WHITE);p.setAlpha(Math.round(145*intensity));p.setStrokeWidth(width*.22f);c.drawPath(shape,p);
    p.setAlpha(255);p.setStrokeCap(Paint.Cap.BUTT);p.setStyle(Paint.Style.FILL);
  }

  private void drawWheel(Canvas c,boolean enabled){
    p.setColor(enabled?0xff13df4e:0xff73777b);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(10);
    c.drawCircle(1388,1355,62,p);p.setStyle(Paint.Style.FILL);
    Path a=new Path();a.moveTo(1330,1344);a.cubicTo(1367,1319,1409,1319,1446,1344);a.lineTo(1446,1363);
    a.cubicTo(1415,1350,1394,1380,1394,1416);a.lineTo(1382,1416);a.cubicTo(1382,1380,1361,1350,1330,1363);a.close();c.drawPath(a,p);
  }
}
