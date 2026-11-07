import com.example.guzlikvoice.*;
import com.google.gson.*;
import kotlin.Unit;
import java.nio.file.*;
import java.util.*;

/** Replays the actual Kotlin pipeline from local, explicitly captured diagnostic evidence. */
public class EvidenceReplay {
 public static void main(String[] args) throws Exception {
  Gson gson=new GsonBuilder().setPrettyPrinting().create();
  JsonObject root=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
  List<float[]> embeddings=Arrays.asList(gson.fromJson(root.get("embeddings"),float[][].class));
  List<SpeakerActivityFusion.Window> windows=Arrays.asList(gson.fromJson(root.get("windows"),SpeakerActivityFusion.Window[].class));
  Integer fixed=args.length>3?Integer.valueOf(args[3]):null;
  long t=System.nanoTime();
  Map<float[],List<Long>> times = new IdentityHashMap<>();
  for(JsonElement el:root.getAsJsonArray("regions")){JsonObject r=el.getAsJsonObject();times.computeIfAbsent(embeddings.get(r.get("embedding").getAsInt()), k->new ArrayList<>()).add(r.has("previewMs")?r.get("previewMs").getAsLong():r.get("startMs").getAsLong());}
  int[] labels=BoundedSpeakerClustering.INSTANCE.cluster(embeddings,1024,()->Unit.INSTANCE,
   sample -> {List<long[]> anchors=new ArrayList<>();for(float[] e:sample)anchors.add(times.getOrDefault(e,List.of()).stream().mapToLong(Long::longValue).toArray());return RobustSpeakerClustering.INSTANCE.clusterSupported(sample,anchors,fixed,()->Unit.INSTANCE);});
  int voiceCount=Arrays.stream(labels).max().orElse(-1)+1;
  float[][] centers=new float[voiceCount][embeddings.get(0).length];
  for(int i=0;i<labels.length;i++)for(int d=0;d<centers[0].length;d++)centers[labels[i]][d]+=embeddings.get(i)[d];
  for(float[] c:centers){double norm=0;for(float x:c)norm+=x*x;norm=Math.sqrt(norm);for(int d=0;d<c.length;d++)c[d]/=norm;}
  boolean[] reliable=new boolean[embeddings.size()];
  for(int i=0;i<labels.length;i++){double dot=0;for(int d=0;d<centers[0].length;d++)dot+=embeddings.get(i)[d]*centers[labels[i]][d];reliable[i]=dot>=.55;}
  List<SpeakerActivityFusion.Evidence> evidence=new ArrayList<>();
  for(JsonElement el:root.getAsJsonArray("regions")){
   JsonObject r=el.getAsJsonObject();
   if(!reliable[r.get("embedding").getAsInt()])continue;
   evidence.add(new SpeakerActivityFusion.Evidence(r.get("startMs").getAsLong(),r.get("endMs").getAsLong(),
    labels[r.get("embedding").getAsInt()],r.get("window").getAsInt()));
  }
  List<SpeakerTimeline.Region> turns=SpeakerActivityFusion.INSTANCE.fuse(windows,evidence,()->Unit.INSTANCE);
  SpeakerTimeline timeline=new SpeakerTimeline(turns);
  List<WhisperHelper.Segment> segments=Arrays.asList(gson.fromJson(Files.readString(Path.of(args[1])),WhisperHelper.Segment[].class));
  List<WhisperHelper.Segment> units=DiarizationUnits.INSTANCE.expand(segments);
  if(units==null)units=segments;
  JsonArray words=new JsonArray();int previous=Integer.MIN_VALUE,switches=0;
  Map<Integer,Integer> counts=new TreeMap<>();StringBuilder text=new StringBuilder();
  for(WhisperHelper.Segment s:units){
   int speaker=timeline.decide(s.getStartMs(),s.getEndMs()).getSpeaker();counts.merge(speaker,1,Integer::sum);
   if(previous!=speaker){if(previous!=Integer.MIN_VALUE)switches++;text.append("\n\n"); if(speaker == -2)text.append("[Говорят одновременно]\n");else if(speaker == -1)text.append("[Говорящий не определён]\n");else text.append("[Говорящий ").append((char)('А'+speaker)).append("]\n");previous=speaker;}
   text.append(s.getText().trim()).append(' ');
   JsonArray u=new JsonArray();u.add(s.getStartMs());u.add(s.getEndMs());u.add(s.getText());u.add(speaker);words.add(u);
  }
  JsonObject out=new JsonObject();out.add("turns",gson.toJsonTree(turns));out.add("units",words);out.add("unit_counts",gson.toJsonTree(counts));
  out.addProperty("switches",switches);out.addProperty("text",text.toString().trim());out.addProperty("speakers",counts.keySet().stream().filter(k->k>=0).count());
  out.addProperty("cached_replay_ms",(System.nanoTime()-t)/1e6);out.addProperty("manually_verified",false);
  Files.writeString(Path.of(args[2]),gson.toJson(out));
  System.out.println("Unit counts: "+counts+", speaker changes: "+switches+", cached replay ms: "+out.get("cached_replay_ms"));
 }
}
