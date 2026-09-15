package kr.carrotvision.hud;
import java.util.*;
public final class LaneTrackCheck {
  static List<float[]> curve(){return Arrays.asList(new float[]{0,1.8f},new float[]{10,2.2f},new float[]{30,5.5f});}
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static boolean near(float a,float b,float eps){return Math.abs(a-b)<=eps;}
  public static void main(String[] args){
    LaneTrack lane=new LaneTrack();
    check(lane.alpha(0)==0 && lane.points.isEmpty(),"No synthetic lane at startup");
    lane.update(.3f,curve(),0);check(near(lane.alpha(120),.3f,.03f),"Low confidence remains visible at raw probability");
    lane.update(.9f,curve(),200);check(near(lane.alpha(320),.9f,.03f),"High confidence maps to opacity");
    check(lane.points.get(2)[1]==5.5f,"Curve lateral coordinates retained");
    lane.update(.5f,curve(),400);check(lane.alpha(625)<.75f && lane.alpha(625)>.45f,"Confidence decrease fades instead of thresholding");
    lane.update(0f,curve(),700);check(lane.alpha(925)>.15f,"Zero confidence fades out");
    check(lane.alpha(1200)==0 && lane.points.isEmpty(),"Zero confidence fully clears");
    lane.update(.8f,curve(),1300);check(near(lane.alpha(1420),.8f,.03f),"Reacquisition uses new confidence");
    check(lane.alpha(1875)>.35f && lane.alpha(1875)<.5f,"Stale data fades");
    check(lane.alpha(2200)==0 && lane.points.isEmpty(),"Stale geometry expires");
    lane.update(.9f,Arrays.asList(new float[]{0,0},new float[]{10,Float.NaN}),2300);
    check(lane.alpha(2500)==0,"Invalid geometry hidden");
    System.out.println("Lane raw-confidence, curvature, fade and stale-data checks passed");
  }
}
