import com.guzlik.voice.*;
import com.google.gson.*;
import kotlin.Unit;
import java.nio.file.*;
import java.util.*;

/** Local-only analysis of explicitly captured embeddings, using the actual Android algorithms. */
public class EvidenceSweep {
    public static void main(String[] args) throws Exception {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject root = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        List<float[]> embeddings = Arrays.asList(gson.fromJson(root.get("embeddings"), float[][].class));
        if (embeddings.size() > 1024) throw new IllegalArgumentException("Use a bounded evaluation fixture (at most 1024 embeddings)");
        List<SpeakerActivityFusion.Window> windows = Arrays.asList(gson.fromJson(root.get("windows"), SpeakerActivityFusion.Window[].class));
        JsonArray regions = root.getAsJsonArray("regions");
        JsonArray results = new JsonArray();
        for (float threshold : new float[]{.15f,.2f,.25f,.3f,.35f,.4f,.45f,.5f,.55f,.6f,.65f,.72f}) {
            int[] labels = AgglomerativeClusterer.INSTANCE.cluster(embeddings, threshold,1,Integer.MAX_VALUE,()->Unit.INSTANCE);
            List<SpeakerActivityFusion.Evidence> evidence = new ArrayList<>();
            for (JsonElement element : regions) {
                JsonObject r=element.getAsJsonObject();
                evidence.add(new SpeakerActivityFusion.Evidence(r.get("startMs").getAsLong(),r.get("endMs").getAsLong(),
                    labels[r.get("embedding").getAsInt()],r.get("window").getAsInt()));
            }
            List<SpeakerTimeline.Region> turns = SpeakerActivityFusion.INSTANCE.fuse(windows,evidence,()->Unit.INSTANCE);
            int k=Arrays.stream(labels).max().orElse(0)+1;
            long[] duration=new long[k];int[] counts=new int[k];
            for(SpeakerTimeline.Region r:turns){duration[r.getSpeaker()]+=r.getEndMs()-r.getStartMs();counts[r.getSpeaker()]++;}
            int[] support=new int[k]; for(int label:labels) support[label]++;
            JsonObject out=new JsonObject();out.add("support",gson.toJsonTree(support));out.addProperty("threshold",threshold);out.add("speech_ms",gson.toJsonTree(duration));
            out.add("turn_count",gson.toJsonTree(counts));out.add("turns",gson.toJsonTree(turns));out.add("labels",gson.toJsonTree(labels));results.add(out);
            System.out.println(threshold+" : "+Arrays.toString(duration)+" support="+Arrays.toString(support));
        }
        Files.writeString(Path.of(args[1]),gson.toJson(results));
    }
}
