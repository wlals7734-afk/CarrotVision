package kr.carrotvision.hud;
import java.util.*;
public final class LaneTrackCheck {
  static List<float[]> curve(){return Arrays.asList(new float[]{0,1.8f},new float[]{10,2.2f},new float[]{30,5.5f});}
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  public static void main(String[] args){
    LaneTrack lane=new LaneTrack();
    check(lane.alpha(0)==0 && lane.points.isEmpty(),"No synthetic lane at startup");
    lane.update(.3f,curve(),0);check(lane.alpha(200)==0,"Low probability remains hidden");
    lane.update(.9f,curve(),200);check(lane.alpha(380)>.99f,"Confident lane fades in");
    check(lane.points.get(2)[1]==5.5f,"Curve lateral coordinates retained");
    lane.update(.5f,curve(),400);check(lane.alpha(420)>.99f,"Hysteresis avoids threshold flicker");
    lane.update(.1f,curve(),450);check(lane.alpha(675)>.4f && lane.alpha(675)<.6f,"Loss fades halfway");
    check(lane.alpha(900)==0 && lane.points.isEmpty(),"Parking lane removed completely");
    lane.update(.9f,curve(),1000);check(lane.alpha(1180)>.99f,"Reacquisition");
    check(lane.alpha(1575)>.4f && lane.alpha(1575)<.6f,"Silent connection loss fades");
    check(lane.alpha(1800)==0 && lane.points.isEmpty(),"Stale geometry expires");
    lane.update(.9f,Arrays.asList(new float[]{0,0},new float[]{10,Float.NaN}),2000);
    check(lane.alpha(2200)==0,"Invalid geometry hidden");
    lane.update(.9f,curve(),2300);lane.alpha(2480);lane.update(.9f,null,2500);
    check(lane.alpha(2950)==0,"Missing lane hidden");
    System.out.println("Lane visibility, curvature, hysteresis, fade and stale-data checks passed");
  }
}
