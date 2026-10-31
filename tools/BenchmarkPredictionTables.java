import ai.boldkeys.SpellEngine;
import java.nio.file.*;
import java.util.*;
import kotlin.Pair;

/** Linux JVM microbenchmark of the actual app parser/lookup, not Android device performance. */
class BenchmarkPredictionTables {
    static volatile Object retained;
    static long heap() throws Exception {
        System.gc(); Thread.sleep(40);
        return Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
    }
    static double median(long[] values) { Arrays.sort(values); return values[values.length/2]/1e6; }
    public static void main(String[] args) throws Exception {
        String text=Files.readString(Path.of(args[0]));
        List<Pair<String,Integer>> words=List.of(new Pair<>("hello",100));
        String[] contexts=text.lines().map(s->s.substring(0,s.indexOf('\t'))).toArray(String[]::new);
        SpellEngine engine=new SpellEngine(words, !args[1].equals("en"));
        for(int i=0;i<8;i++) engine.loadFollowers(text);
        long[] loads=new long[21];
        for(int i=0;i<loads.length;i++) { long start=System.nanoTime(); engine.loadFollowers(text); loads[i]=System.nanoTime()-start; }
        long[] lookup=new long[21]; int guard=0;
        for(int i=0;i<lookup.length;i++) { long start=System.nanoTime(); for(String context:contexts) guard+=engine.nextWords(context,2).size(); lookup[i]=System.nanoTime()-start; }
        engine.loadFollowers(""); long before=heap(); engine.loadFollowers(text); retained=engine; long after=heap();
        System.out.printf(Locale.ROOT,"{\"contexts\":%d,\"load_median_ms\":%.4f,\"lookup_all_contexts_median_ms\":%.4f,\"approx_retained_followers_bytes\":%d,\"guard\":%d}%n",contexts.length,median(loads),median(lookup),after-before,guard);
    }
}
