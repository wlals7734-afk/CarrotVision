package kr.carrotvision.hud;

import java.util.ArrayList;
import java.util.List;

/** Mirrors modelV2 lane confidence as opacity. No synthetic lanes or app-side confidence threshold. */
final class LaneTrack {
  static final long STALE_MS=350, FADE_OUT_MS=450, FADE_IN_MS=120;
  final List<float[]> points=new ArrayList<>();
  private long lastUpdate=-1, transitionAt;
  private float from, target;
  private long duration=FADE_IN_MS;

  void update(float probability,List<float[]> incoming,long now){
    alpha(now);
    lastUpdate=now;
    boolean usable=incoming!=null && incoming.size()>=2;
    float lastX=-1;
    if(usable)for(float[] q:incoming){
      if(q==null || q.length<2 || !Float.isFinite(q[0]) || !Float.isFinite(q[1]) ||
          q[0]<0 || q[0]>150 || q[0]<=lastX){usable=false;break;}
      lastX=q[0];
    }

    float confidence=usable && Float.isFinite(probability)
        ? Math.max(0f,Math.min(1f,probability)) : 0f;
    if(usable){
      points.clear();
      for(float[] q:incoming)points.add(new float[]{q[0],q[1]});
    }
    transition(confidence,now);
  }

  float alpha(long now){
    if(lastUpdate>=0 && now-lastUpdate>STALE_MS)transition(0f,lastUpdate+STALE_MS);
    float value=valueAt(now);
    if(value<=0.001f && target==0f)points.clear();
    return value;
  }

  private float valueAt(long now){
    if(duration<=0)return target;
    float t=Math.max(0f,Math.min(1f,(now-transitionAt)/(float)duration));
    return from+(target-from)*t;
  }

  private void transition(float next,long now){
    next=Math.max(0f,Math.min(1f,next));
    if(Math.abs(next-target)<0.001f)return;
    from=valueAt(now);target=next;transitionAt=now;
    duration=next>from?FADE_IN_MS:FADE_OUT_MS;
  }
}
