package kr.carrotvision.hud;

import java.util.ArrayList;
import java.util.List;

/** Confidence-gated lane geometry with bounded, time-based fading. No synthetic lanes. */
final class LaneTrack {
  static final long STALE_MS=350, FADE_OUT_MS=450, FADE_IN_MS=180;
  final List<float[]> points=new ArrayList<>();
  private boolean detected;
  private long lastUpdate=-1, transitionAt;
  private float from, target;
  private long duration=FADE_IN_MS;

  void update(float probability,List<float[]> incoming,long now){
    // Apply an expired previous packet before accepting a new one.
    alpha(now);
    lastUpdate=now;
    boolean usable=incoming!=null && incoming.size()>=2;
    float lastX=-1;
    if(usable)for(float[] q:incoming){
      if(q==null || q.length<2 || !Float.isFinite(q[0]) || !Float.isFinite(q[1]) ||
          q[0]<0 || q[0]>150 || q[0]<=lastX){usable=false;break;}
      lastX=q[0];
    }
    detected=usable && Float.isFinite(probability) && probability>=(detected?.45f:.60f);
    if(detected){
      points.clear();for(float[] q:incoming)points.add(new float[]{q[0],q[1]});
    }
    transition(detected?1f:0f,now);
  }
  float alpha(long now){
    if(lastUpdate>=0 && now-lastUpdate>STALE_MS){
      detected=false;
      transition(0f,lastUpdate+STALE_MS);
    }
    float value=valueAt(now);
    if(value<=0 && target==0)points.clear();
    return value;
  }
  private float valueAt(long now){
    float t=Math.max(0f,Math.min(1f,(now-transitionAt)/(float)duration));
    return from+(target-from)*t;
  }
  private void transition(float next,long now){
    if(next==target)return;
    from=valueAt(now);target=next;transitionAt=now;
    duration=next>from?FADE_IN_MS:FADE_OUT_MS;
  }
}
